package mchorse.bbs_mod.graphics;

import mchorse.bbs_mod.BBSMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** Optional G5 shader API. No OptiFine classes are linked into the distributable. */
public final class OptiFineShaders
{
    private static final Access API = discover();
    private OptiFineShaders() {}

    private static Access discover()
    {
        try { return new Access(Class.forName("net.optifine.shaders.Shaders")); }
        catch (ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException | LinkageError error)
        {
            BBSMod.LOGGER.warn("OptiFine shader integration is unavailable", error);
            return null;
        }
    }

    public static boolean isLoaded() { return API != null && flag(API.loaded); }
    public static float blockShade(int face)
    {
        return isLoaded() ? ((Number) get(API.blockLight[face])).floatValue() : new float[]{.6F, .8F, .5F}[face];
    }
    public static boolean isShadowPass() { return isLoaded() && flag(API.shadow); }
    public static boolean isWorldPass()
    {
        return isLoaded() && flag(API.world) && (flag(API.deferred) || flag(API.shadow));
    }
    public static void resize() { if (isLoaded()) invoke(API.resize); }

    /** Suspend pack programs, never shaderPackLoaded: BakedQuad and BufferBuilder use
     * that flag to agree on their vertex stride, including outside world rendering. */
    public static LocalPass localPass() { return new LocalPass(); }
    public static final class LocalPass implements AutoCloseable
    {
        private final boolean loaded = isLoaded();
        private final Object previous;
        private final int program;
        private final boolean world, deferred, shadow, glowing;
        private boolean closed;
        private LocalPass()
        {
            if (!loaded)
            {
                previous = null;
                program = 0;
                world = deferred = shadow = glowing = false;
                return;
            }
            previous = get(API.active);
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            world = flag(API.world);
            deferred = flag(API.deferred);
            shadow = flag(API.shadow);
            glowing = flag(API.glowing);
            set(API.world, false);
            set(API.deferred, false);
            set(API.shadow, false);
            set(API.glowing, false);
            /* ProgramNone also detaches OptiFine's cached uniform locations. Merely
             * glUseProgram(0) leaves those hooks pointing into an unrelated BBS shader. */
            invoke(API.use, get(API.none));
            GL20.glUseProgram(0);
        }
        @Override public void close()
        {
            if (!closed && loaded)
            {
                set(API.world, world);
                set(API.deferred, deferred);
                set(API.shadow, shadow);
                set(API.glowing, glowing);
                invoke(API.use, previous);
                GL20.glUseProgram(program);
            }
            closed = true;
        }
    }

    /* What OptiFine's RenderGlobal does before every entity it draws (Shaders.nextEntity ->
     * setEntityId) and what Blockbuster repeats for its render-last actors. BBS draws forms
     * itself, from RenderWorldLastEvent, so nothing ever updated the entityId uniform and the
     * pack kept the id of whichever vanilla entity or tile happened to be drawn last. */
    private static final Method ENTITY_ID = discoverEntityId();
    private static net.minecraft.entity.Entity shaderEntity;
    private static boolean entityIdBroken;

    private static Method discoverEntityId()
    {
        if (API == null) return null;
        try
        {
            Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
            /* setEntityId only writes the uniform; nextEntity also re-selects the program,
             * which EntityPass has already done. Prefer the narrower one. */
            for (String name : new String[]{"setEntityId", "nextEntity"})
            {
                for (Method method : shaders.getMethods())
                {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getName().equals(name) && types.length == 1
                        && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && types[0].isAssignableFrom(net.minecraft.entity.Entity.class)) return method;
                }
            }
        }
        catch (ReflectiveOperationException | LinkageError error)
        {
            BBSMod.LOGGER.warn("OptiFine entity id hook is unavailable", error);
        }
        return null;
    }

    /** Draws made inside this scope are labelled with {@code entity} for the shader pack. */
    public static ActorScope actor(net.minecraft.entity.Entity entity) { return new ActorScope(entity); }
    /** The entity the draws happening right now are labelled with; null outside an {@link #actor} scope. */
    public static net.minecraft.entity.Entity currentActor() { return shaderEntity; }
    public static final class ActorScope implements AutoCloseable
    {
        private final net.minecraft.entity.Entity previous = shaderEntity;
        private ActorScope(net.minecraft.entity.Entity entity) { shaderEntity = entity; }
        @Override public void close() { shaderEntity = previous; }
    }
    private static void applyEntityId()
    {
        if (ENTITY_ID == null || entityIdBroken || shaderEntity == null) return;
        try { ENTITY_ID.invoke(null, shaderEntity); }
        catch (ReflectiveOperationException | RuntimeException error)
        {
            entityIdBroken = true;
            BBSMod.LOGGER.warn("OptiFine entity id hook failed; actors keep the pack's last entity id", error);
        }
    }

    /* Solid forms under a pack: the draw must not blend. GlStateManager only remembers what it last set
     * itself, and OptiFine switches blending (also per draw buffer) with raw GL calls when it selects a
     * program, so disableBlend() issued before the program is bound can be a no-op or be undone. The
     * state is therefore forced after the program is bound, on the real GL state, and handed back. */
    private static boolean forceOpaque;
    public static void forceOpaque(boolean on) { forceOpaque = on; }
    public static final class OpaqueState implements AutoCloseable
    {
        private final boolean blend, mask;
        private OpaqueState()
        {
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            GL11.glDisable(GL11.GL_BLEND);
            /* Per draw buffer switches (glDisablei) are called by reflection: LWJGL 2 builds differ in
             * whether GL30 exposes them, and a missing method must not break compilation or the draw. */
            try
            {
                if (org.lwjgl.opengl.GLContext.getCapabilities().OpenGL30)
                {
                    java.lang.reflect.Method disablei = org.lwjgl.opengl.GL30.class.getMethod("glDisablei", int.class, int.class);
                    for (int i = 0; i < 8; i++) disablei.invoke(null, GL11.GL_BLEND, i);
                }
            }
            catch (Throwable ignored) { }
            GL11.glDepthMask(true);
        }
        @Override public void close()
        {
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            GL11.glDepthMask(mask);
        }
    }
    /** Non-null while the current draw has to come out solid; close it after the draw. */
    public static OpaqueState opaqueState() { return forceOpaque && isWorldPass() ? new OpaqueState() : null; }

    private static final boolean DEBUG = Boolean.getBoolean("bbs.shaderDebug");
    private static int debugCount;
    private static String programName()
    {
        try
        {
            Object program = get(API.active);
            return String.valueOf(program.getClass().getMethod("getName").invoke(program));
        }
        catch (Throwable error) { return "?"; }
    }
    /** -Dbbs.shaderDebug=true: logs the real GL state of the first actor draws (then every 600th). */
    public static void debugDraw(float a)
    {
        if (!DEBUG || !isLoaded()) return;
        int n = debugCount++;
        if (n >= 30 && n % 600 != 0) return;
        java.nio.FloatBuffer color = org.lwjgl.BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        BBSMod.LOGGER.info("[BBS shader debug #{}] program={} id={} world={} shadow={} forceOpaque={} drawAlpha={} "
            + "blend={} blendSrcRGB={} blendDstRGB={} blendSrcA={} blendDstA={} depthTest={} depthMask={} depthFunc={} "
            + "alphaTest={} cull={} currentColor=({},{},{},{}) entity={}",
            programName(), GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), isWorldPass(), isShadowPass(), forceOpaque, a,
            GL11.glIsEnabled(GL11.GL_BLEND), GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
            GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
            GL11.glIsEnabled(GL11.GL_ALPHA_TEST), GL11.glIsEnabled(GL11.GL_CULL_FACE),
            color.get(0), color.get(1), color.get(2), color.get(3), shaderEntity);
    }

    public static EntityPass entities() { return new EntityPass(API.entities); }
    /** Vanilla particles use gbuffers_textured_lit; the shadow program remains
     * owned by OptiFine when a shader pack elects to include these draws. */
    public static EntityPass particles() { return new EntityPass(API.texturedLit); }
    public static EntityPass blocks(net.minecraft.util.BlockRenderLayer layer)
    {
        switch (layer)
        {
            case CUTOUT_MIPPED: return new EntityPass(API.terrainCutoutMip);
            case CUTOUT: return new EntityPass(API.terrainCutout);
            case TRANSLUCENT: return new EntityPass(API.water);
            default: return new EntityPass(API.terrainSolid);
        }
    }
    /** Standard shader-pack hurt/colour overlay, restored independently for every program used. */
    public static OverlayPass overlay(float r, float g, float b, float a) { return new OverlayPass(r,g,b,a); }
    public static final class OverlayPass implements AutoCloseable
    {
        private final float r,g,b,a;
        private final Map<Integer,float[]> previous = new LinkedHashMap<>();
        private final Object uniform = get(API.entityColor);
        private OverlayPass(float r,float g,float b,float a) { this.r=r;this.g=g;this.b=b;this.a=a;apply(); }
        public void apply()
        {
            if (isShadowPass()) return;
            int program = (Integer) invokeOn(API.uniformProgram,uniform);
            if (program <= 0 || program != GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)) return;
            if (!previous.containsKey(program))
            {
                /* OptiFine's cache begins with -Float.MAX_VALUE, not the actual GL
                 * default. Restoring that sentinel makes the following models black. */
                int location=GL20.glGetUniformLocation(program,"entityColor");
                java.nio.FloatBuffer value=org.lwjgl.BufferUtils.createFloatBuffer(4);
                if(location>=0)GL20.glGetUniform(program,location,value);
                previous.put(program,new float[]{value.get(0),value.get(1),value.get(2),value.get(3)});
            }
            invokeOn(API.colorSet,uniform,r,g,b,a);
        }
        @Override public void close()
        {
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int uniformProgram = (Integer) invokeOn(API.uniformProgram,uniform);
            for (Map.Entry<Integer,float[]> entry : previous.entrySet())
            {
                GL20.glUseProgram(entry.getKey()); invokeOn(API.uniformSetProgram,uniform,entry.getKey());
                float[] value=entry.getValue(); invokeOn(API.colorSet,uniform,value[0],value[1],value[2],value[3]);
            }
            invokeOn(API.uniformSetProgram,uniform,uniformProgram); GL20.glUseProgram(program);
        }
    }
    public static final class EntityPass implements AutoCloseable
    {
        private final Object previous = get(API.active);
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private EntityPass(Field target)
        {
            Object selected = get(target);
            /* Keep the native hand and shadow program when those passes own the draw. */
            if (previous == get(API.hand) || previous == get(API.handWater)) selected = previous;
            invoke(API.use, selected);
            GL20.glUseProgram((Integer) get(API.activeId));
            /* After the program is bound: the uniform belongs to whichever program is active. */
            if (selected == get(API.entities)) applyEntityId();
        }
        @Override public void close()
        {
            invoke(API.use, previous);
            GL20.glUseProgram(program);
        }
    }

    private static boolean flag(Field field) { return (Boolean) get(field); }
    private static Object get(Field field)
    {
        try { return field.get(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("OptiFine field " + field.getName(), error); }
    }
    private static void set(Field field, Object value)
    {
        try { field.set(null, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("OptiFine field " + field.getName(), error); }
    }
    private static Object invoke(Method method, Object... args)
    {
        try { return method.invoke(null, args); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("OptiFine method " + method.getName(), error); }
    }
    private static Object invokeOn(Method method, Object owner, Object... args)
    {
        try { return method.invoke(owner, args); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("OptiFine uniform " + method.getName(), error); }
    }
    private static final class Access
    {
        final Field loaded, world, deferred, shadow, glowing, active, activeId, none, entities, hand, handWater, texturedLit, entityColor;
        final Field terrainSolid, terrainCutoutMip, terrainCutout, water;
        final Field[] blockLight = new Field[3];
        final Method use, resize, colorSet, uniformProgram, uniformSetProgram;
        Access(Class<?> shaders) throws ReflectiveOperationException
        {
            loaded = shaders.getField("shaderPackLoaded");
            blockLight[0] = shaders.getField("blockLightLevel06");
            blockLight[1] = shaders.getField("blockLightLevel08");
            blockLight[2] = shaders.getField("blockLightLevel05");
            world = shaders.getField("isRenderingWorld");
            deferred = shaders.getField("isRenderingDfb");
            shadow = shaders.getField("isShadowPass");
            glowing = shaders.getField("isEntitiesGlowing");
            active = shaders.getField("activeProgram");
            activeId = shaders.getField("activeProgramID");
            none = shaders.getField("ProgramNone");
            entities = shaders.getField("ProgramEntities");
            texturedLit = shaders.getField("ProgramTexturedLit");
            terrainSolid = shaders.getField("ProgramTerrainSolid");
            terrainCutoutMip = shaders.getField("ProgramTerrainCutoutMip");
            terrainCutout = shaders.getField("ProgramTerrainCutout");
            water = shaders.getField("ProgramWater");
            entityColor = shaders.getField("uniform_entityColor");
            colorSet = entityColor.getType().getMethod("setValue",float.class,float.class,float.class,float.class);
            uniformProgram = entityColor.getType().getMethod("getProgram");
            uniformSetProgram = entityColor.getType().getMethod("setProgram",int.class);
            hand = shaders.getField("ProgramHand");
            handWater = shaders.getField("ProgramHandWater");
            use = shaders.getMethod("useProgram", active.getType());
            resize = shaders.getMethod("scheduleResize");
        }
    }
}

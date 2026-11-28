package mchorse.bbs_mod.graphics;

import java.nio.FloatBuffer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Lets BBS draw in the middle of vanilla's render loop without changing what vanilla draws next.
 * The actors are drawn at the end of RenderGlobal.renderEntities, and vanilla then draws things
 * that assume its own state (the block selection box, block entities, particles). The raw GL
 * state is restored with glPopAttrib, then GlStateManager's caches are re-synchronised with it,
 * because a cache that disagrees with the real state makes later enable/disable calls no-ops.
 */
public final class GlIsolation implements AutoCloseable
{
    private static final int BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
        | GL11.GL_CURRENT_BIT | GL11.GL_POLYGON_BIT | GL11.GL_LIGHTING_BIT | GL11.GL_LINE_BIT;

    private GlIsolation()
    {
        GL11.glPushAttrib(BITS);
    }

    public static GlIsolation begin()
    {
        return new GlIsolation();
    }

    @Override
    public void close()
    {
        GL11.glPopAttrib();

        sync(GL11.GL_BLEND);
        sync(GL11.GL_ALPHA_TEST);
        sync(GL11.GL_DEPTH_TEST);
        sync(GL11.GL_CULL_FACE);
        sync(GL11.GL_LIGHTING);
        sync(GL11.GL_FOG);

        GlStateManager.tryBlendFuncSeparate(GL11.glGetInteger(0x80C9), GL11.glGetInteger(0x80C8),
            GL11.glGetInteger(0x80CB), GL11.glGetInteger(0x80CA));
        GlStateManager.alphaFunc(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC), GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
        GlStateManager.depthFunc(GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        GlStateManager.depthMask(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));

        FloatBuffer color = BufferUtils.createFloatBuffer(16);

        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        GlStateManager.color(color.get(0), color.get(1), color.get(2), color.get(3));
    }

    private static void sync(int cap)
    {
        boolean on = GL11.glIsEnabled(cap);

        switch (cap)
        {
            case GL11.GL_BLEND: if (on) GlStateManager.enableBlend(); else GlStateManager.disableBlend(); break;
            case GL11.GL_ALPHA_TEST: if (on) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha(); break;
            case GL11.GL_DEPTH_TEST: if (on) GlStateManager.enableDepth(); else GlStateManager.disableDepth(); break;
            case GL11.GL_CULL_FACE: if (on) GlStateManager.enableCull(); else GlStateManager.disableCull(); break;
            case GL11.GL_LIGHTING: if (on) GlStateManager.enableLighting(); else GlStateManager.disableLighting(); break;
            case GL11.GL_FOG: if (on) GlStateManager.enableFog(); else GlStateManager.disableFog(); break;
            default: break;
        }
    }
}

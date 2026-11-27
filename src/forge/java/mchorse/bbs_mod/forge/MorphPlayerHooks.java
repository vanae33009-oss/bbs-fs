package mchorse.bbs_mod.forge;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.morphing.Morph;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;

/** Common-side dimension hooks also apply when vanilla chooses sleep or elytra eye heights. */
public final class MorphPlayerHooks
{
    public static float eyeHeight(EntityPlayer player, float vanilla)
    {
        Morph morph = Morph.getMorph(player);
        Form form = morph == null ? null : morph.getForm();
        if (form == null || !form.hitbox.get()) return vanilla;
        return form.hitboxEyeHeight.get() * form.hitboxHeight.get()
            * (player.isSneaking() ? form.hitboxSneakMultiplier.get() : 1F);
    }
    /** Vanilla's EntityPlayer.onLivingUpdate rewrites the movement attribute from capabilities.walkSpeed
     * every tick, which wipes the speed a form set on morph. The transformer routes that read here, so the
     * form's speed is what vanilla writes back; 0.1 (the default) keeps vanilla's own value. */
    public static float walkSpeed(PlayerCapabilities capabilities, EntityPlayer player)
    {
        hooked = true;
        Morph morph = Morph.getMorph(player);
        Form form = morph == null ? null : morph.getForm();
        float speed = form == null ? 0.1F : form.speed.get();
        return speed != 0.1F ? speed : capabilities.getWalkSpeed();
    }
    /** Set once the transformer's walkSpeed hook has run at least once. */
    private static volatile boolean hooked;
    /** Fallback for when the transformer did not patch onLivingUpdate (another mod got there first, or a
     * different game build): re-applies the form's speed at the end of the player's tick. Does nothing while
     * the hook is alive, so the attribute is not rewritten twice per tick. */
    public static void enforceSpeed(EntityPlayer player)
    {
        if (hooked) return;
        Morph morph = Morph.getMorph(player);
        Form form = morph == null ? null : morph.getForm();
        float speed = form == null ? 0.1F : form.speed.get();
        if (speed == 0.1F) return;
        net.minecraft.entity.ai.attributes.IAttributeInstance attribute =
            player.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attribute != null && attribute.getBaseValue() != speed) attribute.setBaseValue(speed);
    }
    private MorphPlayerHooks() {}
}

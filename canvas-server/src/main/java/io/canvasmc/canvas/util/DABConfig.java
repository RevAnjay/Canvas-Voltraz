package io.canvasmc.canvas.util;

import io.canvasmc.canvas.GlobalConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

public final class DABConfig {

    public static int startDistanceSquared = 144;

    private DABConfig() {}

    public static void initDabEntities() {
        GlobalConfiguration.DAB dab = GlobalConfiguration.getInstance().dab;
        if (dab == null) return;
        startDistanceSquared = dab.startDistance * dab.startDistance; // Canvas - set before guard
        if (BuiltInRegistries.ENTITY_TYPE.keySet().isEmpty()) return; // Canvas - avoid circular class-init during Blocks.<clinit>


        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            entityType.dabEnabled = true;
        }

        final String DEFAULT_PREFIX = Identifier.DEFAULT_NAMESPACE + Identifier.NAMESPACE_SEPARATOR;

        for (String name : dab.blacklistedEntities) {
            if (name == null || name.isBlank()) continue;
            String typeId = name.toLowerCase().startsWith(DEFAULT_PREFIX) ? name : DEFAULT_PREFIX + name;

            EntityType.byString(typeId).ifPresentOrElse(
                entityType -> entityType.dabEnabled = false,
                () -> GlobalConfiguration.LOGGER.warn("Skip unknown entity {} in dab.blacklisted-entities", name)
            );
        }
    }
}

package net.badgersmc.nexus.core

import net.badgersmc.nexus.annotations.ScopeType
import kotlin.reflect.KClass

/**
 * Holds metadata about a registered bean.
 */
data class BeanDefinition(
    val name: String,
    val type: KClass<*>,
    val scope: ScopeType = ScopeType.SINGLETON,
    val origin: BeanOrigin = BeanOrigin.MANUAL_FACTORY,
    val factory: () -> Any
) {
    /** Source-compatible constructor retaining the original factory position. */
    constructor(
        name: String,
        type: KClass<*>,
        scope: ScopeType = ScopeType.SINGLETON,
        factory: () -> Any
    ) : this(name, type, scope, BeanOrigin.MANUAL_FACTORY, factory)
}

/** Describes where a bean definition entered a context candidate. */
enum class BeanOrigin {
    SCANNED_COMPONENT,
    CONFIGURATION,
    EXTERNAL_INSTANCE,
    INFRASTRUCTURE,
    MANUAL_FACTORY
}

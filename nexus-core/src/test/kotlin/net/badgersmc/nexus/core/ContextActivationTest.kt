package net.badgersmc.nexus.core

import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.annotations.PreDestroy
import net.badgersmc.nexus.annotations.ScopeType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContextActivationTest {
    @BeforeEach
    fun reset() {
        ActivationEvents.values.clear()
        RejectedComponent.constructions = 0
        FailingResourceService.resourceOpen = false
    }

    private fun scanned(name: String, type: kotlin.reflect.KClass<*>, scope: ScopeType = ScopeType.SINGLETON) =
        BeanDefinition(name, type, scope, BeanOrigin.SCANNED_COMPONENT) { error("candidate factories must not run") }

    private fun candidate(vararg definitions: BeanDefinition) = ContextCandidate(
        this::class.java.classLoader, "test", definitions.toList()
    )

    @Test
    fun `rejected candidate invokes no constructors or lifecycle callbacks`() {
        val candidate = candidate(scanned("rejected", RejectedComponent::class))
        assertFalse(candidate.receipt.accepted)
        assertThrows<ContextVerificationException> { candidate.activate() }
        assertEquals(0, RejectedComponent.constructions)
        assertEquals(emptyList(), ActivationEvents.values)
    }

    @Test
    fun `activation failure cleans partial component and all installed singleton origins`() {
        val external = ExternalResource("external")
        val configuration = ExternalResource("configuration")
        val candidate = candidate(
            BeanDefinition(
                "externalResource", ExternalResource::class,
                origin = BeanOrigin.EXTERNAL_INSTANCE, factory = { external }
            ),
            BeanDefinition(
                "configurationResource", ConfigResource::class,
                origin = BeanOrigin.CONFIGURATION, factory = { ConfigResource(configuration) }
            ),
            scanned("cleanDependency", CleanDependency::class),
            scanned("failingResourceService", FailingResourceService::class)
        )
        val failure = assertThrows<ContextActivationException> { candidate.activate() }
        assertEquals(
            listOf("configurationResource", "externalResource", "cleanDependency", "failingResourceService"),
            failure.constructedComponents
        )
        assertEquals(
            listOf("configurationResource", "externalResource", "cleanDependency"),
            failure.activatedComponents
        )
        assertEquals(listOf("failingResourceService"), failure.failedComponents)
        assertEquals(
            listOf("failingResourceService", "externalResource", "configurationResource", "cleanDependency"),
            failure.cleanedComponents
        )
        assertFalse(FailingResourceService.resourceOpen)
        assertFalse(external.open)
        assertFalse(configuration.open)
        assertEquals(
            listOf(
                "clean:start", "failing:allocated", "failing:start", "failing:stop",
                "external:stop", "configuration:stop", "clean:stop"
            ),
            ActivationEvents.values
        )
    }

    @Test
    fun `close follows reverse dependency order and destroys each singleton once`() {
        val context = candidate(
            scanned("dataSource", OrderedDataSource::class),
            scanned("repository", OrderedRepository::class),
            scanned("service", OrderedService::class)
        ).activate()
        context.close()
        context.close()
        assertEquals(listOf("service", "repository", "dataSource"), ActivationEvents.values)
    }

    @Test
    fun `prototype scope and interface resolution remain functional`() {
        val port = PortImplementation()
        val context = candidate(
            BeanDefinition("port", PortContract::class, factory = { port }, origin = BeanOrigin.EXTERNAL_INSTANCE),
            scanned("consumer", PortConsumer::class),
            scanned("prototype", PrototypeComponent::class, ScopeType.PROTOTYPE)
        ).activate()
        assertTrue(context.getBean<PortConsumer>().port === port)
        assertFalse(context.getBean<PrototypeComponent>() === context.getBean<PrototypeComponent>())
        context.close()
    }

    @Test
    fun `manual-only context remains functional`() {
        val context = NexusContext.create()
        context.registerBean("value", String::class, "ok")
        assertEquals("ok", context.getBean<String>())
        context.close()
    }
}

internal object ActivationEvents { val values = mutableListOf<String>() }
internal class MissingForRejected
internal class RejectedComponent(val missing: MissingForRejected) {
    init { constructions++ }
    @PostConstruct fun start() { ActivationEvents.values += "rejected:start" }
    companion object { var constructions: Int = 0 }
}
internal class CleanDependency {
    @PostConstruct fun start() { ActivationEvents.values += "clean:start" }
    @PreDestroy fun stop() { ActivationEvents.values += "clean:stop" }
}
internal class FailingResourceService(val dependency: CleanDependency) {
    init {
        resourceOpen = true
        ActivationEvents.values += "failing:allocated"
    }
    @PostConstruct fun start() {
        ActivationEvents.values += "failing:start"
        error("expected lifecycle failure")
    }
    @PreDestroy fun stop() {
        resourceOpen = false
        ActivationEvents.values += "failing:stop"
    }
    companion object { var resourceOpen = false }
}
internal class ExternalResource(private val label: String) {
    var open = true
    @PreDestroy fun stop() {
        open = false
        ActivationEvents.values += "$label:stop"
    }
}
internal class ConfigResource(private val resource: ExternalResource) {
    @PreDestroy fun stop() = resource.stop()
}
internal class OrderedDataSource { @PreDestroy fun stop() { ActivationEvents.values += "dataSource" } }
internal class OrderedRepository(val dataSource: OrderedDataSource) {
    @PreDestroy fun stop() { ActivationEvents.values += "repository" }
}
internal class OrderedService(val repository: OrderedRepository) {
    @PreDestroy fun stop() { ActivationEvents.values += "service" }
}
internal interface PortContract
internal class PortImplementation : PortContract
internal class PortConsumer(val port: PortContract)
internal class PrototypeComponent

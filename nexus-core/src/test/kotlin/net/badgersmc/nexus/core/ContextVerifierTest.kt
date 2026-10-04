package net.badgersmc.nexus.core

import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.annotations.PreDestroy
import net.badgersmc.nexus.annotations.Qualifier
import net.badgersmc.nexus.annotations.ScopeType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContextVerifierTest {
    private fun definition(name: String, type: kotlin.reflect.KClass<*>) = BeanDefinition(
        name = name,
        type = type,
        scope = ScopeType.SINGLETON,
        factory = { error("verification must not invoke factories") },
        origin = BeanOrigin.SCANNED_COMPONENT
    )

    @Test
    fun `valid graph is accepted with deterministic dependency order and hash`() {
        val definitions = listOf(definition("service", ValidService::class), definition("repository", ValidRepository::class))
        val first = ContextVerifier.verify(definitions)
        val second = ContextVerifier.verify(definitions.reversed())

        assertTrue(first.receipt.accepted)
        assertEquals(listOf("repository", "service"), first.receipt.activationOrder)
        assertEquals(listOf("service", "repository"), first.receipt.shutdownOrder)
        assertEquals(first.receipt.graphHash, second.receipt.graphHash)
        assertEquals(first.receipt.toJson(), second.receipt.toJson())
    }

    @Test
    fun `missing dependency is typed and rejected`() {
        val receipt = ContextVerifier.verify(listOf(definition("service", ValidService::class))).receipt
        assertIs<VerificationIssue.MissingDependency>(receipt.issues.single())
    }

    @Test
    fun `ambiguous dependency is typed and qualified dependency resolves`() {
        val providers = listOf(definition("one", OnePort::class), definition("two", TwoPort::class))
        val ambiguous = ContextVerifier.verify(providers + definition("consumer", AmbiguousConsumer::class)).receipt
        assertIs<VerificationIssue.AmbiguousDependency>(ambiguous.issues.single())

        val qualified = ContextVerifier.verify(providers + definition("consumer", QualifiedConsumer::class)).receipt
        assertTrue(qualified.accepted)
    }

    @Test
    fun `direct and multi node cycles include the complete closed path`() {
        val direct = ContextVerifier.verify(listOf(definition("direct", DirectCycle::class))).receipt
        assertEquals(listOf("direct", "direct"), assertIs<VerificationIssue.CircularDependency>(direct.issues.single()).cycle)

        val multi = ContextVerifier.verify(
            listOf(definition("cycleA", CycleA::class), definition("cycleB", CycleB::class), definition("cycleC", CycleC::class))
        ).receipt
        assertEquals(listOf("cycleA", "cycleB", "cycleC", "cycleA"), assertIs<VerificationIssue.CircularDependency>(multi.issues.single()).cycle)
    }

    @Test
    fun `duplicate name and invalid lifecycle are rejected`() {
        val duplicate = ContextVerifier.verify(listOf(definition("same", OnePort::class), definition("same", TwoPort::class))).receipt
        assertIs<VerificationIssue.DuplicateBeanName>(duplicate.issues.single())

        val lifecycle = ContextVerifier.verify(listOf(definition("invalid", InvalidLifecycle::class))).receipt
        assertIs<VerificationIssue.InvalidLifecycleMethod>(lifecycle.issues.single())
    }

    @Test
    fun `duplicate candidate index edge is typed`() {
        val repeated = definition("one", OnePort::class)
        val receipt = ContextVerifier.verify(listOf(repeated, repeated)).receipt
        assertIs<VerificationIssue.DuplicateTypeIndexEntry>(receipt.issues.single())
    }

    @Test
    fun `graph hash changes with graph semantics`() {
        val one = ContextVerifier.verify(listOf(definition("one", OnePort::class))).receipt
        val two = ContextVerifier.verify(listOf(definition("two", OnePort::class))).receipt
        assertNotEquals(one.graphHash, two.graphHash)
    }

    @Test
    fun `receipt JSON preserves typed issue evidence`() {
        val missing = ContextVerifier.verify(listOf(definition("service", ValidService::class))).receipt.toJson()
        assertTrue(missing.contains("\"schemaVersion\":2"))
        assertTrue(missing.contains("\"parameter\":\"repository\""))
        assertTrue(missing.contains("\"requestedType\":\"net.badgersmc.nexus.core.ValidRepository\""))
        assertTrue(missing.contains("\"qualifier\":null"))

        val ambiguous = ContextVerifier.verify(
            listOf(
                definition("one", OnePort::class), definition("two", TwoPort::class),
                definition("consumer", AmbiguousConsumer::class)
            )
        ).receipt.toJson()
        assertTrue(ambiguous.contains("\"providers\":[\"one\",\"two\"]"))

        val cycle = ContextVerifier.verify(listOf(definition("direct", DirectCycle::class))).receipt.toJson()
        assertTrue(cycle.contains("\"cycle\":[\"direct\",\"direct\"]"))

        val lifecycle = ContextVerifier.verify(listOf(definition("invalid", InvalidLifecycle::class))).receipt.toJson()
        assertTrue(lifecycle.contains("\"method\":\"start(kotlin.String)\""))
        assertTrue(lifecycle.contains("\"reason\":\"callbacks must not declare arguments\""))
    }
}

internal class ValidRepository
internal class ValidService(val repository: ValidRepository)
internal interface Port
internal class OnePort : Port
internal class TwoPort : Port
internal class AmbiguousConsumer(val port: Port)
internal class QualifiedConsumer(@Qualifier("two") val port: Port)
internal class DirectCycle(val direct: DirectCycle)
internal class CycleA(val cycleB: CycleB)
internal class CycleB(val cycleC: CycleC)
internal class CycleC(val cycleA: CycleA)
internal class InvalidLifecycle {
    @PostConstruct fun start(value: String) = value
}

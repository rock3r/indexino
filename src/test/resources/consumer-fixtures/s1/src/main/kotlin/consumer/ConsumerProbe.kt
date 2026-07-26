package consumer

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import java.nio.file.Path

public suspend fun main(args: Array<String>) {
    ConsumerProbe().touch()
    Indexino.connectBlocking(Path.of(args.single())).use { indexino ->
        // Root Gradle scopes always resolve the whole build; the facade requires an explicit
        // includingDependencies() so the published provenance matches the observed closure.
        indexino
            .refresh(RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies()))
            .await()
        indexino.snapshot().use { snapshot ->
            val symbols =
                snapshot.findSymbols(JavaModelProbe.touchSymbol(), JavaModelProbe.firstPage())
            val touch = symbols.items.firstOrNull() ?: error("Expected the touch symbol")
            val references =
                snapshot.findReferences(
                    JavaModelProbe.referencesTo(touch),
                    JavaModelProbe.firstPage(),
                )
            JavaModelProbe.assertReferences(references)
            println("INDEXINO_S1_CONSUMER_OK")
        }
    }
}

private class ConsumerProbe {
    fun touch() = Unit
}

package dev.sebastiano.indexino.api;

import java.nio.file.Path;

final class FacadeJavaConsumer {
    private FacadeJavaConsumer() {}

    static RefreshRequest compileAgainstPublicFacade(Path workspace) {
        Indexino indexino = Indexino.connectBlocking(workspace);
        try {
            IndexScope scope = IndexScope.gradle(":app").includingDependencies();
            return RefreshRequest.forScope(scope);
        } finally {
            indexino.close();
        }
    }
}

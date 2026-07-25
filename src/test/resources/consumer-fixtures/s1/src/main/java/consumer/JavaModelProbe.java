package consumer;

import dev.sebastiano.indexino.model.QueryOptions;
import dev.sebastiano.indexino.model.QueryPage;
import dev.sebastiano.indexino.model.Reference;
import dev.sebastiano.indexino.model.ReferenceQuery;
import dev.sebastiano.indexino.model.Symbol;
import dev.sebastiano.indexino.model.SymbolQuery;

public final class JavaModelProbe {
    private JavaModelProbe() {}

    public static SymbolQuery touchSymbol() {
        return SymbolQuery.named("touch");
    }

    public static QueryOptions firstPage() {
        return QueryOptions.page(1);
    }

    public static ReferenceQuery referencesTo(Symbol symbol) {
        return ReferenceQuery.to(symbol.getId());
    }

    public static void assertReferences(QueryPage<Reference> references) {
        if (references.getItems().isEmpty()) {
            throw new IllegalStateException("Expected a reference to touch");
        }
    }
}

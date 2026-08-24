val outerCalls = context.calls.find(
    CallQuery.to("ComposableA"),
    QueryOptions.page(500),
)

for (outer in outerCalls.items) {
    val content = outer.arguments.firstOrNull { argument ->
        // Dogfood (#43): trailing lambdas often land as LAMBDA with a null resolvedName
        // until parameter names are available on the call fact.
        argument.resolvedName == "content" ||
            argument.kind == ArgumentKind.TRAILING_LAMBDA ||
            argument.kind == ArgumentKind.LAMBDA
    } ?: continue

    val containsB = content.nestedCallIds
        .mapNotNull { id -> context.calls.byId(id) }
        .any { call -> call.calleeName == "ComposableB" }

    if (containsB) {
        context.report(
            ScriptFinding.at(outer.range)
                .message("ComposableA content contains ComposableB")
        )
    }
}

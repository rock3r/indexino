@file:Suppress("FunctionName", "unused")

@Target(AnnotationTarget.FUNCTION)
annotation class Composable

typealias M = Modifier

object Modifier {
    fun padding(all: Int): Modifier = this

    fun fillMaxWidth(): Modifier = this

    fun background(color: Int): Modifier = this

    fun then(other: Modifier): Modifier = other

    fun composed(block: () -> Unit): Modifier = this
}

fun screenModifier(): Modifier = Modifier.padding(16)

fun forwardedPadding(base: Modifier = Modifier): Modifier = base.padding(8)

fun outerModifier(): Modifier = Modifier.fillMaxWidth()

@Composable
fun DirectChainSample() {
    Text(
        text = "hello",
        modifier = Modifier.padding(8).fillMaxWidth(),
    )
}

@Composable
fun OrderedArgumentSample() {
    Text(modifier = Modifier.padding(4), text = "ordered")
}

@Composable
fun ThenChainSample() {
    Row(modifier = Modifier.padding(4).then(Modifier.fillMaxWidth()))
}

@Composable
fun HelperModifierSample() {
    Box(modifier = screenModifier())
}

@Composable
fun ConditionalModifierSample() {
    val wide = true
    Column(modifier = if (wide) Modifier.fillMaxWidth() else Modifier.padding(8))
}

@Composable
fun ComposedModifierSample() {
    Box(modifier = Modifier.composed {})
}

@Composable
fun AliasModifierSample() {
    Text(text = "alias", modifier = M.padding(8).fillMaxWidth())
}

@Composable
fun NestedCallSample() {
    Box(modifier = Modifier.padding(outerModifier()))
}

@Composable
fun ForwardedParameterSample() {
    Box(modifier = forwardedPadding())
}

@Composable
fun UnresolvedModifierSample() {
    Box(modifier = maybeModifier())
}

fun maybeModifier(): Modifier = Modifier.padding(4)

@Composable
fun NoModifierSample() {
    Column {}
}

@Composable
fun Text(text: String, modifier: Modifier = Modifier) {}

@Composable
fun Row(modifier: Modifier = Modifier) {}

@Composable
fun Box(modifier: Modifier = Modifier) {}

@Composable
fun Column(modifier: Modifier = Modifier) {}

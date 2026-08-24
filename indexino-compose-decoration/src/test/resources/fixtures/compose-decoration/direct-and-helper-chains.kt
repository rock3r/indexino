@Target(AnnotationTarget.FUNCTION)
annotation class Composable

object Modifier {
    fun padding(all: Int): Modifier = this
    fun fillMaxWidth(): Modifier = this
    fun then(other: Modifier): Modifier = other
    fun composed(block: () -> Unit): Modifier = this
}

fun screenModifier(): Modifier = Modifier.padding(16)

@Composable
fun DirectChainSample() {
    Text(
        text = "hello",
        modifier = Modifier.padding(8).fillMaxWidth(),
    )
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

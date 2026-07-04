@Target(AnnotationTarget.FUNCTION)
annotation class Composable

@Composable
fun Panel() {
    SelectionContainer {
        ActionButton()
    }
}

@Composable
fun ActionButton() {}

fun SelectionContainer(content: @Composable () -> Unit) {}

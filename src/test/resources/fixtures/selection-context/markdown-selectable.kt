@Target(AnnotationTarget.FUNCTION)
annotation class Composable

@Composable
fun Article() {
    Markdown(selectable = true) {
        Text()
    }
}

@Composable
fun Text() {}

fun Markdown(selectable: Boolean = false, content: @Composable () -> Unit) {}

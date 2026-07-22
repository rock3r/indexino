package sample

class Renderer {
    fun render() = Unit
}

fun callRenderer(renderer: Renderer) {
    renderer.render()
}

@Composable
fun Content() {
    SelectionContainer { ActionButton() }
}

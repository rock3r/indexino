package sample

/** Renders [Content] and documents its [render] member. */
class Renderer {
    /** @return the rendered result; see [Renderer] */
    fun render() = Unit
}

/**
 * Calls [Renderer.render].
 *
 * @param renderer the [Renderer] to invoke
 * @see Content
 */
fun callRenderer(renderer: Renderer) {
    renderer.render()
}

@Composable
fun Content() {
    SelectionContainer { ActionButton() }
}

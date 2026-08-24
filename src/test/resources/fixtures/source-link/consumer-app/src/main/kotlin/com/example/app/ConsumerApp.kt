import com.example.lib.ProviderLib

class ConsumerApp {
    private val lib = ProviderLib()
    fun run(): String = lib.greet()
}

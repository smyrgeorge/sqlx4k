package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test

class ValueEncoderRegistryTests {

    // A couple of tiny ValueEncoder impls for small types. Their `decode` is never
    // exercised by the registry (the registry only stores/looks-up encoders), so it
    // simply errors out per the interface contract.
    private class IntEncoder : ValueEncoder<Int> {
        override fun encode(value: Int): Any = value.toString()
        override fun decode(value: ResultSet.Row.Column): Int = error("n/a")
    }

    private class StringEncoder : ValueEncoder<String> {
        override fun encode(value: String): Any = value
        override fun decode(value: ResultSet.Row.Column): String = error("n/a")
    }

    private class BooleanEncoder : ValueEncoder<Boolean> {
        override fun encode(value: Boolean): Any = if (value) "TRUE" else "FALSE"
        override fun decode(value: ResultSet.Row.Column): Boolean = error("n/a")
    }

    // ========================================================================================
    // register / get / getTyped / reified get
    // ========================================================================================

    @Test
    fun `register by KClass then get by KClass returns the same encoder`() {
        val enc = IntEncoder()
        val reg = ValueEncoderRegistry().register(Int::class, enc)
        assertThat(reg.get(Int::class)).isSameInstanceAs(enc)
    }

    @Test
    fun `register by KClass then reified get returns the same encoder`() {
        val enc = IntEncoder()
        val reg = ValueEncoderRegistry().register(Int::class, enc)
        assertThat(reg.get<Int>()).isSameInstanceAs(enc)
    }

    @Test
    fun `register by KClass then getTyped returns the same encoder`() {
        val enc = IntEncoder()
        val reg = ValueEncoderRegistry().register(Int::class, enc)
        assertThat(reg.getTyped(Int::class)).isSameInstanceAs(enc)
    }

    @Test
    fun `reified register registers under the reified type`() {
        val enc = StringEncoder()
        val reg = ValueEncoderRegistry().register(enc)
        assertThat(reg.get(String::class)).isSameInstanceAs(enc)
        assertThat(reg.getTyped(String::class)).isSameInstanceAs(enc)
        assertThat(reg.get<String>()).isSameInstanceAs(enc)
    }

    @Test
    fun `register returns the same registry instance`() {
        val reg = ValueEncoderRegistry()
        val returned = reg.register(Int::class, IntEncoder())
        assertThat(returned).isSameInstanceAs(reg)
    }

    @Test
    fun `reified register returns the same registry instance`() {
        val reg = ValueEncoderRegistry()
        val returned = reg.register(StringEncoder())
        assertThat(returned).isSameInstanceAs(reg)
    }

    @Test
    fun `get for an unregistered type returns null across all getters`() {
        val reg = ValueEncoderRegistry().register(Int::class, IntEncoder())
        assertThat(reg.get(String::class)).isNull()
        assertThat(reg.getTyped(String::class)).isNull()
        assertThat(reg.get<String>()).isNull()
    }

    // ========================================================================================
    // EMPTY
    // ========================================================================================

    @Test
    fun `EMPTY get returns null`() {
        assertThat(ValueEncoderRegistry.EMPTY.get(Int::class)).isNull()
        assertThat(ValueEncoderRegistry.EMPTY.getTyped(Int::class)).isNull()
        assertThat(ValueEncoderRegistry.EMPTY.get<Int>()).isNull()
    }

    // ========================================================================================
    // plus operator
    // ========================================================================================

    @Test
    fun `plus produces the union of both registries`() {
        val intEnc = IntEncoder()
        val boolEnc = BooleanEncoder()
        val left = ValueEncoderRegistry().register(Int::class, intEnc)
        val right = ValueEncoderRegistry().register(Boolean::class, boolEnc)

        val result = left + right

        assertThat(result.get(Int::class)).isSameInstanceAs(intEnc)
        assertThat(result.get(Boolean::class)).isSameInstanceAs(boolEnc)
    }

    @Test
    fun `plus lets the right operand win on key collision`() {
        val leftInt = IntEncoder()
        val rightInt = IntEncoder()
        val left = ValueEncoderRegistry().register(Int::class, leftInt)
        val right = ValueEncoderRegistry().register(Int::class, rightInt)

        val result = left + right

        assertThat(result.get(Int::class)).isSameInstanceAs(rightInt)
        assertThat(result.get(Int::class)).isNotSameInstanceAs(leftInt)
    }

    @Test
    fun `plus does not mutate either operand`() {
        val leftInt = IntEncoder()
        val leftStr = StringEncoder()
        val rightInt = IntEncoder()
        val rightBool = BooleanEncoder()
        val left = ValueEncoderRegistry()
            .register(Int::class, leftInt)
            .register(String::class, leftStr)
        val right = ValueEncoderRegistry()
            .register(Int::class, rightInt)
            .register(Boolean::class, rightBool)

        left + right

        // left keeps its own Int encoder and never gains Boolean.
        assertThat(left.get(Int::class)).isSameInstanceAs(leftInt)
        assertThat(left.get(String::class)).isSameInstanceAs(leftStr)
        assertThat(left.get(Boolean::class)).isNull()
        // right keeps its own Int encoder and never gains String.
        assertThat(right.get(Int::class)).isSameInstanceAs(rightInt)
        assertThat(right.get(Boolean::class)).isSameInstanceAs(rightBool)
        assertThat(right.get(String::class)).isNull()
    }

    @Test
    fun `plus returns a new instance distinct from both operands`() {
        val left = ValueEncoderRegistry().register(Int::class, IntEncoder())
        val right = ValueEncoderRegistry().register(Boolean::class, BooleanEncoder())

        val result = left + right

        assertThat(result).isNotSameInstanceAs(left)
        assertThat(result).isNotSameInstanceAs(right)
    }

    @Test
    fun `plus with EMPTY on the right keeps left entries`() {
        val intEnc = IntEncoder()
        val left = ValueEncoderRegistry().register(Int::class, intEnc)

        val result = left + ValueEncoderRegistry.EMPTY

        assertThat(result).isNotSameInstanceAs(left)
        assertThat(result.get(Int::class)).isSameInstanceAs(intEnc)
    }

    @Test
    fun `plus with EMPTY on the left keeps right entries`() {
        val intEnc = IntEncoder()
        val right = ValueEncoderRegistry().register(Int::class, intEnc)

        val result = ValueEncoderRegistry.EMPTY + right

        assertThat(result).isNotSameInstanceAs(right)
        assertThat(result.get(Int::class)).isSameInstanceAs(intEnc)
    }
}

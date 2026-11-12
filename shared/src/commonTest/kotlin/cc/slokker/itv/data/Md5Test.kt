package cc.slokker.itv.data

import kotlin.test.Test
import kotlin.test.assertEquals

class Md5Test {
    @Test
    fun rfc1321Vectors() {
        assertEquals("D41D8CD98F00B204E9800998ECF8427E", Md5.hexUpper(""))
        assertEquals("0CC175B9C0F1B6A831C399E269772661", Md5.hexUpper("a"))
        assertEquals("900150983CD24FB0D6963F7D28E17F72", Md5.hexUpper("abc"))
        assertEquals("F96B697D7CB7938D525A2F31AAF161D0", Md5.hexUpper("message digest"))
        assertEquals("57EDF4A22BE3C955AC49DA2E2107B67A", Md5.hexUpper("1234567890".repeat(8)))
    }
}

package cc.slokker.itv.ui.player.goatcore

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpTsDataSourceTest {
    private fun ts(n: Int) = ByteArray(188 * n) { if (it % 188 == 0) 0x47 else 0 }

    @Test
    fun tsSemRtpPassaInteiro() {
        val p = ts(7)
        assertEquals(0, UdpTsDataSource.payloadStart(p, p.size))
        assertEquals(p.size, UdpTsDataSource.payloadEnd(p, p.size))
    }

    @Test
    fun rtpTiraOCabecalho() {
        val p = byteArrayOf(0x80.toByte(), 33, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0) + ts(7)
        assertEquals(12, UdpTsDataSource.payloadStart(p, p.size))
        assertEquals(p.size, UdpTsDataSource.payloadEnd(p, p.size))
    }

    @Test
    fun rtpComCsrcExtensaoEEnchimento() {
        // V=2, P=1, X=1, CC=1: 12 + 4 (CSRC) + 4 (cabeçalho da extensão) + 8 (2 palavras), e 4 bytes de enchimento.
        val header = byteArrayOf(0xB1.toByte(), 33, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2) + ByteArray(8)
        val p = header + ts(7) + byteArrayOf(0, 0, 0, 4)
        assertEquals(header.size, UdpTsDataSource.payloadStart(p, p.size))
        assertEquals(p.size - 4, UdpTsDataSource.payloadEnd(p, p.size))
    }
}

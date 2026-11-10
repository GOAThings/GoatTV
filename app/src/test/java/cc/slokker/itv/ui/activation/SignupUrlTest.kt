package cc.slokker.itv.ui.activation

import org.junit.Assert.assertEquals
import org.junit.Test

class SignupUrlTest {
    @Test
    fun addsTheSignupTabToTheApproveUrl() {
        assertEquals("https://thegoat.systems/activate?code=AB12CD&signup=1", signupUrl("https://thegoat.systems/activate?code=AB12CD"))
        assertEquals("https://thegoat.systems/activate?signup=1", signupUrl("https://thegoat.systems/activate"))
    }
}

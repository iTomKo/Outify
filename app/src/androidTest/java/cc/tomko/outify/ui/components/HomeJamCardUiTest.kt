package cc.tomko.outify.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.tomko.outify.R
import cc.tomko.outify.core.jam.Jam
import cc.tomko.outify.core.jam.JamMember
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeJamCardUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inactiveCardOpensControlsWithoutRequiringPlayback() {
        var opens = 0
        compose.setContent {
            MaterialTheme {
                HomeJamCard(jam = null, onClick = { opens++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.jam_start_headline)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.jam_start))
            .assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun activeCardTracksMembershipAndReturnsToStartWhenJamEnds() {
        val jam = mutableStateOf<Jam?>(Jam(members = members(1)))
        var opens = 0
        compose.setContent {
            MaterialTheme {
                HomeJamCard(jam = jam.value, onClick = { opens++ })
            }
        }
        compose.onNodeWithText(context.getString(R.string.home_jam_active)).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.jam_listeners, 1, 1))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.jam_manage)).performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            jam.value = Jam(members = members(3))
        }
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.jam_listeners, 3, 3))
            .assertIsDisplayed()
        compose.runOnIdle { jam.value = null }
        compose.onNodeWithText(context.getString(R.string.jam_start)).assertIsDisplayed()
    }

    private fun members(count: Int) = List(count) { index ->
        JamMember(id = "$index", name = "Member $index")
    }
}

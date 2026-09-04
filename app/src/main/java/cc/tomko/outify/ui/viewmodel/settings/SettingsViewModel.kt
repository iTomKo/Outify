package cc.tomko.outify.ui.viewmodel.settings

import androidx.lifecycle.ViewModel
import cc.tomko.outify.core.Session
import cc.tomko.outify.core.spirc.SpircWrapper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val session: Session,
    val spirc: SpircWrapper,
) : ViewModel() {
}
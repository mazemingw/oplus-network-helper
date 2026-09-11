package be.mygod.vpnhotspot.manage

import android.content.Intent
import android.view.View
import androidx.databinding.BaseObservable
import androidx.recyclerview.widget.RecyclerView
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.databinding.ListitemManageBinding
import be.mygod.vpnhotspot.net.TetherOffloadManager
import timber.log.Timber

object ManageBar : Manager() {
    private const val TAG = "ManageBar"
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val SETTINGS_1 = "com.android.settings.Settings\$TetherSettingsActivity"
    private const val SETTINGS_2 = "com.android.settings.TetherSettings"

    object Data : BaseObservable() {
        val offloadEnabled get() = TetherOffloadManager.enabled
    }
    class ViewHolder(binding: ListitemManageBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        init {
            binding.data = Data
            binding.root.setOnClickListener(this)
        }

        override fun onClick(v: View?) = start(itemView.context::startActivity)
    }

    override val type: Int get() = VIEW_TYPE_MANAGE

    fun start(startActivity: (Intent) -> Unit) {
        val intent = Intent().setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent.setClassName(SETTINGS_PACKAGE, SETTINGS_1))
        } catch (e1: RuntimeException) {
            try {
                startActivity(intent.setClassName(SETTINGS_PACKAGE, SETTINGS_2))
                Timber.w(e1, "%s: fallback to %s after %s failed", TAG, SETTINGS_2, SETTINGS_1)
            } catch (e2: RuntimeException) {
                Timber.w(e1, "%s: %s failed", TAG, SETTINGS_1)
                Timber.w(e2, "%s: %s failed", TAG, SETTINGS_2)
            }
        }

    }
}

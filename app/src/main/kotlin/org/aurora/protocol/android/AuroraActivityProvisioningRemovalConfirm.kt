package org.aurora.protocol.android

import android.app.AlertDialog

internal fun AuroraActivity.confirmRemoveProvisioning() {
    if (!currentControls().removeProvisioningEnabled) {
        return
    }
    if (removeProvisioningDialog?.isShowing == true) {
        return
    }
    removeProvisioningDialog = AlertDialog.Builder(this)
        .setTitle(R.string.remove_provisioning_title)
        .setMessage(R.string.remove_provisioning_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.action_remove_provisioning) { _, _ -> removeProvisioning() }
        .setOnDismissListener { removeProvisioningDialog = null }
        .show()
}

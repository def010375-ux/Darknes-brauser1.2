package com.darknes.tor

import android.app.assist.AssistStructure
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

private data class AutofillField(val id: android.view.autofill.AutofillId, val hints: List<String>, val inputType: Int, val domain: String?)

class DarknesAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: android.os.CancellationSignal, callback: FillCallback) {
        if (!VaultSessionCache.isUnlocked) { callback.onSuccess(null); return }
        val fields = mutableListOf<AutofillField>()
        fun visit(node: AssistStructure.ViewNode) {
            val id = node.autofillId
            if (id != null) fields.add(AutofillField(id, node.autofillHints?.toList() ?: emptyList(), node.inputType, node.webDomain))
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) { callback.onSuccess(null); return }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        val username = fields.firstOrNull { field -> field.hints.any { it == "username" || it == "emailAddress" } || (field.inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) != 0 }
        val password = fields.firstOrNull { field -> field.hints.any { it == "password" || it == "newPassword" } || (field.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 }
        if (password == null) { callback.onSuccess(null); return }
        val domain = (password.domain ?: username?.domain)?.lowercase()?.removePrefix("www.")
        val matching = VaultSessionCache.entries.filter { entry ->
            val savedHost = runCatching { java.net.URI(if (entry.website.contains("://")) entry.website else "https://${entry.website}").host?.lowercase()?.removePrefix("www.") }.getOrNull()
            savedHost != null && domain != null && (savedHost == domain || savedHost.endsWith(".$domain") || domain.endsWith(".$savedHost"))
        }
        val candidates = if (matching.isNotEmpty()) matching else VaultSessionCache.entries
        val response = FillResponse.Builder()
        candidates.take(20).forEach { entry ->
            val label = if (entry.title.isBlank()) entry.username.ifBlank { "Сохранённый пароль" } else entry.title
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "Darknes Vault · $label") }
            val builder = Dataset.Builder(presentation)
            username?.let { if (entry.username.isNotBlank()) builder.setValue(it.id, AutofillValue.forText(entry.username)) }
            builder.setValue(password.id, AutofillValue.forText(entry.password))
            response.addDataset(builder.build())
        }
        callback.onSuccess(if (candidates.isEmpty()) null else response.build())
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) {
        // Credentials are saved explicitly inside Darknes Vault; no plaintext is persisted by this service.
        callback.onSuccess()
    }
}
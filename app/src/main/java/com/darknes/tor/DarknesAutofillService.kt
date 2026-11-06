package com.darknes.tor

import android.app.PendingIntent
import android.content.Intent
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.widget.RemoteViews

internal data class AutofillTargetFields(val username: AutofillId?, val password: AutofillId, val domain: String?)

internal object DarknesAutofillResponses {
    fun findFields(structure: android.app.assist.AssistStructure): AutofillTargetFields? {
        val fields = mutableListOf<Triple<AutofillId, List<String>, Pair<Int, String?>>>()
        fun visit(node: android.app.assist.AssistStructure.ViewNode) {
            node.autofillId?.let { id -> fields.add(Triple(id, node.autofillHints?.toList() ?: emptyList(), node.inputType to node.webDomain)) }
            for (index in 0 until node.childCount) visit(node.getChildAt(index))
        }
        for (index in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(index).rootViewNode)
        val username = fields.firstOrNull { it.second.any { hint -> hint == "username" || hint == "emailAddress" } || (it.third.first and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) != 0 }
        val password = fields.firstOrNull { it.second.any { hint -> hint == "password" || hint == "newPassword" } || (it.third.first and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 } ?: return null
        return AutofillTargetFields(username?.first, password.first, password.third.second ?: username?.third?.second)
    }

    fun buildResponse(context: android.content.Context, fields: AutofillTargetFields, entries: List<VaultEntry>): FillResponse? {
        val domain = fields.domain?.lowercase()?.removePrefix("www.") ?: return null
        val matching = entries.filter { entry ->
            val host = runCatching { java.net.URI(if (entry.website.contains("://")) entry.website else "https://${entry.website}").host?.lowercase()?.removePrefix("www.") }.getOrNull()
            host != null && (host == domain || domain.endsWith(".$host"))
        }
        if (matching.isEmpty()) return null
        val response = FillResponse.Builder()
        matching.take(20).forEach { entry ->
            val label = entry.title.ifBlank { entry.username.ifBlank { "Сохранённый пароль" } }
            val presentation = RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "Darknes Vault · $label") }
            val dataset = android.service.autofill.Dataset.Builder(presentation)
            fields.username?.let { id -> if (entry.username.isNotBlank()) dataset.setValue(id, android.view.autofill.AutofillValue.forText(entry.username)) }
            dataset.setValue(fields.password, android.view.autofill.AutofillValue.forText(entry.password))
            response.addDataset(dataset.build())
        }
        return response.build()
    }
}

class DarknesAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: android.os.CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) { callback.onSuccess(null); return }
        val fields = DarknesAutofillResponses.findFields(structure)
        if (fields == null) { callback.onSuccess(null); return }
        if (VaultSessionCache.isUnlocked) {
            callback.onSuccess(DarknesAutofillResponses.buildResponse(this, fields, VaultSessionCache.entries))
            return
        }
        if (!DarknesVaultCrypto.isCreated(this)) { callback.onSuccess(null); return }
        val authIntent = Intent(this, AutofillAuthActivity::class.java)
        val pending = PendingIntent.getActivity(this, (System.nanoTime() and 0x7fffffff).toInt(), authIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "Разблокировать Darknes Vault") }
        val ids = listOfNotNull(fields.username, fields.password).distinct().toTypedArray()
        val response = FillResponse.Builder().setAuthentication(ids, pending.intentSender, presentation).build()
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: android.service.autofill.SaveRequest, callback: android.service.autofill.SaveCallback) { callback.onSuccess() }
}
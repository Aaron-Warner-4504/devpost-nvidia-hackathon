package ai.enthral.sentinel.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * ContactsContract lookups, only answering yes/no. No contact data is copied, stored or sent.
 * Every method returns null when READ_CONTACTS is not granted (or the provider fails).
 */
class AndroidContactChecker(private val context: Context) : ContactChecker {
    private fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    private inline fun query(block: () -> Boolean): Boolean? {
        if (!granted()) return null
        return try {
            block()
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    override fun isSavedContactUri(uri: String): Boolean? = query {
        context.contentResolver.query(Uri.parse(uri), arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }

    override fun isSavedNumber(number: String): Boolean? = query {
        if (number.count { it.isDigit() } < 6) return@query false
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }

    override fun isContactName(name: String): Boolean? = query {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE",
            arrayOf(name.trim()),
            null,
        )?.use { it.moveToFirst() } ?: false
    }
}

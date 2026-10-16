package com.pyqcr.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.gson.Gson
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.ImageThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Photo Vault — a password-protected private area reached from the left menu.
 *
 * - Copy images from the gallery into app-private storage (invisible to other
 *   apps and to the album's own MediaStore-backed views).
 * - Optionally encrypt each entry with AES-256-GCM using a key derived from
 *   the vault password (PBKDF2) — without the password the file is useless.
 * - Hide/unhide entries; hidden ones only appear after toggling "Show hidden".
 *
 * Layout:
 *   vault/
 *     <id>          plaintext entry (no extension — name lives in the meta)
 *     <id>.vault    encrypted entry (magic + salt + iv + ciphertext)
 *     <id>.meta     JSON: VaultMeta(name, encrypted, hidden, dateAdded)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    // Auth state
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var authed by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Vault state
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var galleryImages by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }

    // Import picker state
    var showImportPicker by remember { mutableStateOf(false) }
    var importSelection by remember { mutableStateOf(setOf<String>()) }
    var encryptImport by remember { mutableStateOf(true) }

    // Viewer state
    var viewingEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var viewPath by remember { mutableStateOf<String?>(null) }
    var viewError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // Refresh the entry list from disk whenever the vault is (re)opened
    fun refreshEntries() {
        entries = VaultStorage.listEntries(context)
    }

    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images ->
            galleryImages = images
        }
    }

    LaunchedEffect(authed) {
        if (authed) refreshEntries()
    }

    /** Open the viewer for [entry], decrypting on demand when encrypted. */
    val openViewer: (VaultEntry) -> Unit = { entry ->
        viewingEntry = entry
        viewPath = null
        viewError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (entry.encrypted) {
                    VaultStorage.decryptToCache(context, entry, password)
                } else {
                    VaultStorage.entryFile(context, entry).absolutePath
                }
            }
            if (result == null) {
                viewError = "Wrong password or corrupted file"
            } else {
                viewPath = result
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // 1) First run: set the vault password
                !VaultStorage.hasPassword(context) -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text(
                            text = "🔒 Set a vault password",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "It unlocks the vault and protects encrypted entries. " +
                                "If you forget it, encrypted images cannot be recovered.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (errorMsg != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = errorMsg!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                when {
                                    password.length < 4 -> {
                                        errorMsg = "Password must be at least 4 characters"
                                    }
                                    password != confirmPassword -> {
                                        errorMsg = "Passwords do not match"
                                    }
                                    else -> {
                                        VaultStorage.setPassword(context, password)
                                        authed = true
                                        errorMsg = null
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set Password")
                        }
                    }
                }

                // 2) Password locked
                !authed -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(48.dp))
                        Text(
                            text = "🔒 Photo Vault",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Enter your vault password to unlock",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (errorMsg != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = errorMsg!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (VaultStorage.verifyPassword(context, password)) {
                                    authed = true
                                    errorMsg = null
                                } else {
                                    errorMsg = "Wrong password"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unlock")
                        }
                    }
                }

                // 3) Vault contents
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Toolbar row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    showImportPicker = true
                                    importSelection = emptySet()
                                },
                                enabled = !importing
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Import from Gallery")
                            }
                            Spacer(Modifier.weight(1f))
                            FilterChip(
                                selected = showHidden,
                                onClick = { showHidden = !showHidden },
                                label = { Text("Show hidden") }
                            )
                        }

                        val visibleEntries = remember(entries, showHidden) {
                            if (showHidden) entries
                            else entries.filter { !it.hidden }
                        }
                        val hiddenCount = entries.count { it.hidden }
                        val encryptedCount = entries.count { it.encrypted }

                        Text(
                            text = "${entries.size} in vault · $encryptedCount encrypted · " +
                                "$hiddenCount hidden (${visibleEntries.size} shown)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        if (visibleEntries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (entries.isEmpty())
                                            "No images in the vault yet"
                                        else "No visible entries — toggle \"Show hidden\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    if (entries.isEmpty()) {
                                        Text(
                                            text = "Import copies from your gallery into this " +
                                                "private area (optionally encrypted).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 32.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color.White)
                            ) {
                                gridItems(visibleEntries, key = { it.id }) { entry ->
                                    VaultCell(
                                        entry = entry,
                                        previewPath = if (entry.encrypted) null
                                        else VaultStorage.entryFile(context, entry).absolutePath,
                                        onTap = { openViewer(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ===================== Import picker dialog =====================
    if (showImportPicker) {
        AlertDialog(
            onDismissRequest = { showImportPicker = false },
            title = { Text("Import ${importSelection.size} image(s)") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = encryptImport,
                            onCheckedChange = { encryptImport = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Encrypt with vault password")
                    }
                    if (encryptImport) {
                        Text(
                            text = "Encrypted files are unreadable without the vault password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .background(Color.Black)
                    ) {
                        gridItems(galleryImages, key = { it.uri }) { image ->
                            val selected = image.uri in importSelection
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clickable {
                                        importSelection = if (selected)
                                            importSelection - image.uri
                                        else
                                            importSelection + image.uri
                                    }
                            ) {
                                ImageThumbnail(
                                    imageUri = image.uri,
                                    modifier = Modifier.fillMaxSize(),
                                    backgroundColor = Color.Black
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(3.dp, Color.Green)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0x40000000)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✓", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        importing = true
                        scope.launch {
                            var ok = 0
                            var fail = 0
                            for (uri in importSelection) {
                                val name = VaultStorage.galleryDisplayName(context, uri)
                                val result = withContext(Dispatchers.IO) {
                                    VaultStorage.importFromGallery(
                                        context = context,
                                        uri = uri,
                                        displayName = name,
                                        encrypted = encryptImport,
                                        password = if (encryptImport) password else null
                                    )
                                }
                                if (result) ok++ else fail++
                            }
                            importing = false
                            showImportPicker = false
                            refreshEntries()
                            Toast.makeText(
                                context,
                                if (fail == 0) "Imported $ok image(s) to the vault"
                                else "Imported $ok, $fail failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = importSelection.isNotEmpty() && !importing
                ) {
                    Text(if (importing) "Importing…" else "Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ===================== Viewer dialog =====================
    viewingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = {
                viewingEntry = null
                viewPath = null
                viewError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val currentPath = viewPath
                    if (currentPath != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(currentPath)
                                .crossfade(true)
                                .build(),
                            contentDescription = entry.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else if (viewError != null) {
                        Text(
                            text = viewError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.encrypted) {
                            Text(
                                text = "🔒 Encrypted",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (entry.hidden) {
                            Text(
                                text = "🙈 Hidden",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        VaultStorage.setHidden(context, entry, !entry.hidden)
                        refreshEntries()
                        viewingEntry = null
                        viewPath = null
                        viewError = null
                    }) {
                        Text(if (entry.hidden) "Unhide" else "Hide")
                    }
                    TextButton(
                        onClick = {
                            VaultStorage.deleteEntry(context, entry)
                            refreshEntries()
                            viewingEntry = null
                            viewPath = null
                            viewError = null
                            Toast.makeText(context, "Removed from vault", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewingEntry = null
                    viewPath = null
                    viewError = null
                }) {
                    Text("Close")
                }
            }
        )
    }
}

/** One tile in the vault grid. Encrypted entries show a lock instead of a preview. */
@Composable
private fun VaultCell(
    entry: VaultEntry,
    previewPath: String?,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onTap)
            .background(Color.White)
    ) {
        if (previewPath != null) {
            ImageThumbnail(
                imageUri = previewPath,
                modifier = Modifier.fillMaxSize(),
                backgroundColor = Color.White
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF37474F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Encrypted",
                    tint = Color(0xFFFFD740),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // Name badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        ) {
            Text(
                text = entry.displayName,
                color = Color.White,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Hidden badge
        if (entry.hidden) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0xCC000000), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "🙈",
                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                )
            }
        }
    }
}

// ===================== Vault domain model =====================

/** Lightweight entry description built from the on-disk metadata. */
data class VaultEntry(
    val id: String,
    val displayName: String,
    val encrypted: Boolean,
    val hidden: Boolean,
    val dateAdded: Long
)

/** Per-entry metadata persisted as <id>.meta next to the data file. */
data class VaultMeta(
    val name: String = "",
    val encrypted: Boolean = false,
    val hidden: Boolean = false,
    val dateAdded: Long = 0L
)

// ===================== Crypto (PBKDF2 + AES-256-GCM) =====================

/**
 * Password hashing and file encryption.
 *
 * Files use a per-file random salt; the encryption key is derived from the
 * vault password with PBKDF2-HMAC-SHA256 (100k iterations, 256 bits).
 * Encrypted layout: "PYQVAULT1" + salt(16) + iv(12) + ciphertext.
 */
object VaultCrypto {

    private const val MAGIC = "PYQVAULT1"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** Hash for password verification; also usable as a MAC-style check. */
    fun hashPassword(password: String, salt: ByteArray): ByteArray = deriveKey(password, salt)

    fun encryptBytes(bytes: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES")
        )
        val ciphertext = cipher.doFinal(bytes)
        val iv = cipher.iv
        val out = ByteArray(MAGIC.length + salt.size + iv.size + ciphertext.size)
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        System.arraycopy(magicBytes, 0, out, 0, magicBytes.size)
        var p = magicBytes.size
        System.arraycopy(salt, 0, out, p, salt.size)
        p += salt.size
        System.arraycopy(iv, 0, out, p, iv.size)
        p += iv.size
        System.arraycopy(ciphertext, 0, out, p, ciphertext.size)
        return out
    }

    /** Decrypt; returns null on wrong password / tampered data. */
    fun decryptBytes(data: ByteArray, password: String): ByteArray? {
        return try {
            val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
            if (data.size < magicBytes.size + SALT_BYTES + IV_BYTES) return null
            if (!data.copyOfRange(0, magicBytes.size).contentEquals(magicBytes)) return null
            var p = magicBytes.size
            val salt = data.copyOfRange(p, p + SALT_BYTES)
            p += SALT_BYTES
            val iv = data.copyOfRange(p, p + IV_BYTES)
            p += IV_BYTES
            val ciphertext = data.copyOfRange(p, data.size)
            val key = deriveKey(password, salt)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }
}

// ===================== Password storage (EncryptedSharedPreferences) =====================

private fun getVaultPrefs(context: Context): android.content.SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "pyqcr_vault_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }
}

/** True once a vault password has been set. */
fun hasVaultPassword(context: Context): Boolean {
    return getVaultPrefs(context)?.getString("vault_auth", null) != null
}

/** Store base64(salt):base64(hash) of the password. */
fun setVaultPassword(context: Context, password: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hash = VaultCrypto.hashPassword(password, salt)
    val stored = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP) + ":" +
        android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    try {
        getVaultPrefs(context)?.edit()?.putString("vault_auth", stored)?.apply()
    } catch (_: Exception) {
    }
}

/** Constant-time password check against the stored hash. */
fun verifyVaultPassword(context: Context, password: String): Boolean {
    val stored = getVaultPrefs(context)?.getString("vault_auth", null) ?: return false
    val parts = stored.split(":")
    if (parts.size != 2) return false
    return try {
        val salt = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val expected = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val actual = VaultCrypto.hashPassword(password, salt)
        MessageDigest.isEqual(expected, actual)
    } catch (e: Exception) {
        false
    }
}

// ===================== Vault storage =====================

object VaultStorage {

    private val gson = Gson()

    fun vaultDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "vault")
        dir.mkdirs()
        return dir
    }

    fun entryFile(context: Context, entry: VaultEntry): File {
        val suffix = if (entry.encrypted) ".vault" else ""
        return File(vaultDir(context), entry.id + suffix)
    }

    fun hasPassword(context: Context): Boolean = hasVaultPassword(context)

    fun setPassword(context: Context, password: String) {
        setVaultPassword(context, password)
    }

    fun verifyPassword(context: Context, password: String): Boolean =
        verifyVaultPassword(context, password)

    /** List vault entries, newest first. Orphaned entries (missing data file) are skipped. */
    fun listEntries(context: Context): List<VaultEntry> {
        val dir = vaultDir(context)
        val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: return emptyList()
        return metas.mapNotNull { metaFile ->
            val id = metaFile.name.removeSuffix(".meta")
            val meta = try {
                gson.fromJson(metaFile.readText(), VaultMeta::class.java)
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            val suffix = if (meta.encrypted) ".vault" else ""
            val dataFile = File(dir, id + suffix)
            if (!dataFile.exists()) return@mapNotNull null
            VaultEntry(
                id = id,
                displayName = meta.name,
                encrypted = meta.encrypted,
                hidden = meta.hidden,
                dateAdded = meta.dateAdded
            )
        }.sortedByDescending { it.dateAdded }
    }

    /** Best-effort display name for a gallery content URI. */
    fun galleryDisplayName(context: Context, uri: String): String {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) c.getString(0)
                else uri.substringAfterLast('/')
            } ?: uri.substringAfterLast('/')
        } catch (e: Exception) {
            uri.substringAfterLast('/')
        }
    }

    /**
     * Copy [uri] from the gallery into the vault. When [encrypted], the bytes
     * are AES-256-GCM encrypted with a key derived from [password].
     * Returns true on success.
     */
    fun importFromGallery(
        context: Context,
        uri: String,
        displayName: String,
        encrypted: Boolean,
        password: String?
    ): Boolean {
        return try {
            if (encrypted && password.isNullOrEmpty()) return false
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))
                ?.use { it.readBytes() } ?: return false
            if (bytes.isEmpty()) return false

            val id = "v" + System.currentTimeMillis() + "_" + (Math.random() * 100000).toInt()
            val dataFile = if (encrypted) {
                val enc = VaultCrypto.encryptBytes(bytes, password!!)
                File(vaultDir(context), "$id.vault").apply { writeBytes(enc) }
            } else {
                File(vaultDir(context), id).apply { writeBytes(bytes) }
            }
            if (!dataFile.exists() || dataFile.length() == 0L) {
                dataFile.delete()
                return false
            }
            val meta = VaultMeta(
                name = displayName,
                encrypted = encrypted,
                hidden = false,
                dateAdded = System.currentTimeMillis()
            )
            File(vaultDir(context), "$id.meta").writeText(gson.toJson(meta))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Decrypt an encrypted entry into a cache file for viewing. Null on failure. */
    fun decryptToCache(context: Context, entry: VaultEntry, password: String): String? {
        return try {
            val data = entryFile(context, entry).readBytes()
            val plain = VaultCrypto.decryptBytes(data, password) ?: return null
            val cacheDir = File(context.cacheDir, "vault_view").apply { mkdirs() }
            val outFile = File(cacheDir, "${entry.id}.jpg")
            outFile.writeBytes(plain)
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Hide (or unhide) an entry. */
    fun setHidden(context: Context, entry: VaultEntry, hidden: Boolean) {
        val metaFile = File(vaultDir(context), "${entry.id}.meta")
        val meta = try {
            gson.fromJson(metaFile.readText(), VaultMeta::class.java)
        } catch (e: Exception) {
            VaultMeta()
        }
        try {
            metaFile.writeText(gson.toJson(meta.copy(hidden = hidden)))
        } catch (_: Exception) {
        }
    }

    /** Remove an entry (data file + metadata). */
    fun deleteEntry(context: Context, entry: VaultEntry) {
        try {
            entryFile(context, entry).delete()
        } catch (_: Exception) {
        }
        try {
            File(vaultDir(context), "${entry.id}.meta").delete()
        } catch (_: Exception) {
        }
    }
}
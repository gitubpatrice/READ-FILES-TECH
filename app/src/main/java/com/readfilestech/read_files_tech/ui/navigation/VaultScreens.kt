package com.readfilestech.read_files_tech.ui.navigation

/**
 * The screens the open vault stays open on: its own — the list ([Feature.VAULT]), a viewer on one of its files,
 * the import of a folder — and the choosers it opens (the picker, the explorer choosing). Any other destination
 * locks it, as leaving its screen by Back does (`VaultViewModel.onCleared`): a tile opening the scanner over the
 * vault, or a recent opened from the picker of its export, left it open — the key and the viewer's decrypted copy
 * kept, the list back without the password at the next Back (security audit of the 3.0, B1-02).
 */
object VaultScreens {
    /** True when the destination [route] (its pattern), its arguments read by [argument], keeps the vault open. */
    fun keepOpen(route: String?, argument: (String) -> String?): Boolean = when (route) {
        Routes.FEATURE -> argument(Routes.ARG_FEATURE) == Feature.VAULT.name
        Routes.VIEWER -> argument(Routes.ARG_VAULT) != null
        Routes.VAULT_IMPORT, Routes.PICKER -> true
        Routes.EXPLORER -> argument(Routes.ARG_PICK) != null
        else -> false
    }
}

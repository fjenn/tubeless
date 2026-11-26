package app.tubeless

/**
 * Names from before the app was called Tubeless (it is a fork of TubeHaus, see ACKNOWLEDGEMENTS.md).
 * Kept only so that existing installs, saved PINs, backups and a TubeHaus server keep working;
 * nothing new is written under them.
 */
object Legacy {
    /** the data folder's old name on Android and iOS (moved to "tubeless" on the first start) */
    const val DATA_DIR = "tubehaus"
    /** what a person's PIN hash was salted with */
    const val PIN_SALT = "tubehaus-person"
    /** the backup file format of TubeHaus (read on import) */
    const val BACKUP_FORMAT = "tubehaus-backup"
    /** the export format name a TubeHaus server answers to */
    const val SERVER_FORMAT = "tubehaus"
}

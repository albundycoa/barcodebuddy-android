package de.bulling.barcodebuddyscanner.Update;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Locale;

/**
 * Beschreibung der neuesten veröffentlichten Version (die Datei latest.json im GitHub-Release).
 */
public final class UpdateInfo {

	/** Versionsname, z. B. "0.2.0" */
	public String version;
	/** Versionscode, steigt mit jeder Version */
	public int    version_code;
	/** Adresse der APK */
	public String url;
	/** Größe der APK in Byte */
	public long   size;
	/** SHA-256 der APK als Hexadezimalzahl */
	public String sha256;
	/** Was sich geändert hat (kurzer Text, darf fehlen) */
	public String notes;
	/** Datum der Veröffentlichung (nur zur Anzeige) */
	public String built;

	/**
	 * Liest und prüft die Beschreibung. Eine unvollständige oder verdächtige Datei (kein https, keine gültige
	 * Prüfsumme) wird abgelehnt, damit daraus kein Download wird.
	 *
	 * @throws IllegalArgumentException wenn die Angaben nicht stimmen
	 */
	public static UpdateInfo parse(String json) {
		UpdateInfo info;
		try {
			info = new Gson().fromJson(json, UpdateInfo.class);
		} catch (JsonSyntaxException e) {
			throw new IllegalArgumentException("Keine gültige Update-Beschreibung", e);
		}
		if (info == null || info.version == null || info.version.trim().isEmpty() || info.version_code <= 0) {
			throw new IllegalArgumentException("Version fehlt");
		}
		if (info.url == null || !info.url.startsWith("https://")) {
			throw new IllegalArgumentException("Adresse fehlt oder ist nicht https");
		}
		if (info.sha256 == null || !info.sha256.matches("(?i)[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Prüfsumme fehlt");
		}
		info.sha256 = info.sha256.toLowerCase(Locale.ROOT);
		return info;
	}

	public boolean isNewerThan(int installedVersionCode) {
		return version_code > installedVersionCode;
	}

	public String toJson() {
		return new Gson().toJson(this);
	}
}

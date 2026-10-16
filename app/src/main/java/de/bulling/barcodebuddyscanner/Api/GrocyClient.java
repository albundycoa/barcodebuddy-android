package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Lesender Zugriff auf die Grocy-API. Die Aufrufe blockieren und gehören deshalb
 * auf einen Hintergrund-Thread.
 */
public class GrocyClient {

	/** Antwort mit einem anderen Status als 2xx */
	public static class HttpException extends IOException {
		public final int statusCode;

		HttpException(int statusCode) {
			super("HTTP " + statusCode);
			this.statusCode = statusCode;
		}
	}

	private final String  baseUrl;
	private final String  apiKey;
	private final boolean isUnsafe;

	public GrocyClient(String url, String apiKey, boolean isUnsafe) {
		this.baseUrl  = normalizeUrl(url);
		this.apiKey   = apiKey;
		this.isUnsafe = isUnsafe;
	}

	/** Grocy-Adresse mit abschließendem "/api/", egal ob mit oder ohne eingegeben. */
	public static String normalizeUrl(String url) {
		String result = url == null ? "" : url.trim();
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		if (!result.endsWith("/api")) {
			result += "/api";
		}
		return result + "/";
	}

	public JsonElement get(String path) throws IOException {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(6000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("GROCY-API-KEY", apiKey);
			connection.setRequestProperty("Accept", "application/json");
			if (isUnsafe && connection instanceof HttpsURLConnection) {
				UnsafeSslHelper.applyUnsafeSsl((HttpsURLConnection) connection);
			}

			int status = connection.getResponseCode();
			if (status < 200 || status >= 300) {
				throw new HttpException(status);
			}
			StringBuilder body = new StringBuilder();
			InputStream is = connection.getInputStream();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					body.append(line);
				}
			}
			return JsonParser.parseString(body.toString());
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			// Kaputtes JSON, z. B. weil unter der Adresse gar kein Grocy antwortet
			throw new IOException("Unerwartete Antwort", e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}
}

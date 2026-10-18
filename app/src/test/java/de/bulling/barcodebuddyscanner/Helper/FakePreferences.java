package de.bulling.barcodebuddyscanner.Helper;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences im Arbeitsspeicher, damit die Logik ohne Gerät getestet werden kann. */
final class FakePreferences implements SharedPreferences {

	private final Map<String, Object> values = new HashMap<>();

	@Override
	public Map<String, ?> getAll() {
		return new HashMap<>(values);
	}

	@Override
	public String getString(String key, String defValue) {
		Object v = values.get(key);
		return v == null ? defValue : (String) v;
	}

	@Override
	public Set<String> getStringSet(String key, Set<String> defValues) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getInt(String key, int defValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getLong(String key, long defValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public float getFloat(String key, float defValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean contains(String key) {
		return values.containsKey(key);
	}

	@Override
	public Editor edit() {
		return new Editor() {
			private final Map<String, Object> pending = new HashMap<>();

			@Override
			public Editor putString(String key, String value) {
				pending.put(key, value);
				return this;
			}

			@Override
			public Editor putStringSet(String key, Set<String> value) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Editor putInt(String key, int value) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Editor putLong(String key, long value) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Editor putFloat(String key, float value) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Editor putBoolean(String key, boolean value) {
				throw new UnsupportedOperationException();
			}

			@Override
			public Editor remove(String key) {
				pending.put(key, null);
				return this;
			}

			@Override
			public Editor clear() {
				values.clear();
				return this;
			}

			@Override
			public boolean commit() {
				apply();
				return true;
			}

			@Override
			public void apply() {
				for (Map.Entry<String, Object> e : pending.entrySet()) {
					if (e.getValue() == null) {
						values.remove(e.getKey());
					} else {
						values.put(e.getKey(), e.getValue());
					}
				}
			}
		};
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
	}
}

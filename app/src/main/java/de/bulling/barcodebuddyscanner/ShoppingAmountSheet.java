package de.bulling.barcodebuddyscanner;

import android.app.Activity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.StockOverview;

/**
 * Maske von unten: ein Produkt mit Menge und Einheit auf die Einkaufsliste setzen. Steht es schon darauf, zeigt sie die bisherige
 * Menge zum Ändern (und zum Entfernen); beim Wechsel der Einheit erscheint die Menge dieser Einheit. Gemeinsam genutzt von der
 * Bestandsseite und von "Zur Einkaufsliste hinzufügen".
 */
final class ShoppingAmountSheet {

	/** Was der Nutzer gewählt hat. */
	interface Listener {
		void onSave(double amount, long quId, String unitName);

		void onRemove(StockOverview.ListRow row);
	}

	/** Eine Einheit zur Auswahl; id 0 = unbekannt (dann gilt Grocys Standard). */
	private static final class UnitChoice {
		final long   id;
		final String name;

		UnitChoice(long id, String name) {
			this.id   = id;
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private ShoppingAmountSheet() {
	}

	/**
	 * @param quPurchase Einkaufseinheit des Produkts (0 = unbekannt), die zuerst angeboten wird
	 * @param quStock    Lagereinheit (0 = unbekannt)
	 * @param fallback   Name einer Einheit, wenn keine Nummern bekannt sind
	 * @param existing   Einträge des Produkts, die schon auf der Liste stehen (leer = noch nicht)
	 */
	static void show(Activity activity, String productName, long quPurchase, long quStock, String fallback,
					 Map<Long, String> unitNames, final List<StockOverview.ListRow> existing, final Listener listener) {
		final List<UnitChoice> choices = new ArrayList<>();
		add(choices, unitNames, quPurchase);
		add(choices, unitNames, quStock);
		for (StockOverview.ListRow row : existing) {
			add(choices, unitNames, row.quId);
		}
		if (choices.isEmpty()) {
			choices.add(new UnitChoice(0, fallback == null ? "" : fallback));
		}

		final BottomSheetDialog sheet = new BottomSheetDialog(activity);
		View view = activity.getLayoutInflater().inflate(R.layout.sheet_shopping_add, null);
		sheet.setContentView(view);
		sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
		sheet.getBehavior().setSkipCollapsed(true);
		((TextView) view.findViewById(R.id.sheet_product)).setText(productName);
		final TextInputEditText amountInput = view.findViewById(R.id.sheet_amount);
		final TextInputLayout amountLayout = view.findViewById(R.id.sheet_amount_layout);
		final MaterialButton addButton = view.findViewById(R.id.sheet_add);
		final View removeButton = view.findViewById(R.id.sheet_remove);
		AmountInput.apply(amountInput);
		final MaterialAutoCompleteTextView unitInput = view.findViewById(R.id.sheet_unit);
		unitInput.setAdapter(new ArrayAdapter<>(activity,
				com.google.android.material.R.layout.m3_auto_complete_simple_item, choices));

		// Vorausgewählt: die Einheit des vorhandenen Eintrags, sonst die Einkaufseinheit
		UnitChoice first = choices.get(0);
		if (!existing.isEmpty()) {
			for (UnitChoice choice : choices) {
				if (choice.id == existing.get(0).quId) {
					first = choice;
				}
			}
		}
		final UnitChoice[] selected = {first};
		final Runnable fill = () -> {
			StockOverview.ListRow row = rowFor(existing, selected[0].id);
			amountInput.setText(row == null ? "1" : Money.formatPlain(row.amount).replaceAll(",?0+$", ""));
			amountInput.selectAll();
			amountLayout.setError(null);
			addButton.setText(row == null ? R.string.stock_list_add : R.string.stock_list_save);
			removeButton.setVisibility(row == null ? View.GONE : View.VISIBLE);
		};
		unitInput.setText(first.name, false);
		fill.run();
		unitInput.setOnItemClickListener((parent, v, position, id) -> {
			selected[0] = choices.get(position);
			fill.run();
		});
		view.findViewById(R.id.sheet_cancel).setOnClickListener(v -> sheet.dismiss());
		removeButton.setOnClickListener(v -> {
			StockOverview.ListRow row = rowFor(existing, selected[0].id);
			sheet.dismiss();
			if (row != null) {
				listener.onRemove(row);
			}
		});
		addButton.setOnClickListener(v -> {
			Double amount = Money.parse(String.valueOf(amountInput.getText()));
			if (amount == null || amount <= 0) {
				amountLayout.setError(activity.getString(R.string.stock_list_err_amount));
				return;
			}
			sheet.dismiss();
			listener.onSave(amount, selected[0].id, selected[0].name);
		});
		sheet.show();
		amountInput.requestFocus();
	}

	private static StockOverview.ListRow rowFor(List<StockOverview.ListRow> rows, long quId) {
		for (StockOverview.ListRow row : rows) {
			if (row.quId == quId) {
				return row;
			}
		}
		return null;
	}

	private static void add(List<UnitChoice> choices, Map<Long, String> names, long quId) {
		String name = names.get(quId);
		if (quId <= 0 || name == null) {
			return;
		}
		for (UnitChoice choice : choices) {
			if (choice.id == quId) {
				return;
			}
		}
		choices.add(new UnitChoice(quId, name));
	}
}

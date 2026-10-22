package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Zur Einkaufsliste hinzufügen: Produktsuche im Katalog des Handys. Ein Treffer führt zur Mengenwahl; was es nicht gibt, lässt
 * sich als freier Eintrag anlegen (danach die Produktgruppe wählen, damit er richtig einsortiert wird).
 */
public class AddActivity extends Activity implements State.Listener {

	/** Zeile der Trefferliste: ein Produkt oder "Freier Eintrag". */
	private static final class Row {
		final JsonObject product;
		final boolean    free;

		Row(JsonObject product, boolean free) {
			this.product = product;
			this.free    = free;
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
	private EditText search;
	private String query = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_add);
		search = findViewById(R.id.search);
		RecyclerView list = findViewById(R.id.results);
		list.setLayoutManager(new LinearLayoutManager(this));
		adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stock, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				final Row row = rows.get(position);
				TextView name = holder.itemView.findViewById(R.id.name);
				TextView sub = holder.itemView.findViewById(R.id.sub);
				if (row.free) {
					name.setText(getString(R.string.add_free, query.trim()));
					sub.setText(R.string.add_free_sub);
					holder.itemView.setOnClickListener(v -> startActivity(new Intent(AddActivity.this, GroupActivity.class)
							.putExtra(AmountActivity.EXTRA_NAME, query.trim())));
					return;
				}
				name.setText(row.product.get("n").getAsString());
				String group = row.product.has("g") ? row.product.get("g").getAsString() : "";
				sub.setText(group);
				sub.setVisibility(group.isEmpty() ? View.GONE : View.VISIBLE);
				holder.itemView.setOnClickListener(v -> startActivity(new Intent(AddActivity.this, AmountActivity.class)
						.putExtra(AmountActivity.EXTRA_NAME, row.product.get("n").getAsString())
						.putExtra(AmountActivity.EXTRA_PRODUCT, row.product.get("id").getAsLong())));
			}

			@Override
			public int getItemCount() {
				return rows.size();
			}
		};
		list.setAdapter(adapter);
		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				query = s.toString();
				refresh();
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		PhoneLink.requestCatalog(this);
		refresh();
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		super.onStop();
	}

	@Override
	public void onStateChanged() {
		refresh();
	}

	private void refresh() {
		JsonObject catalog = State.loadCatalog(this);
		JsonArray products = State.array(catalog, "p");
		rows.clear();
		for (JsonObject hit : CatalogSearch.search(products, query)) {
			rows.add(new Row(hit, false));
		}
		// Was es noch nicht gibt, lässt sich nach der Eingabe eines Namens als freier Eintrag anlegen
		if (!query.trim().isEmpty()) {
			rows.add(new Row(null, true));
		}
		adapter.notifyDataSetChanged();
	}
}

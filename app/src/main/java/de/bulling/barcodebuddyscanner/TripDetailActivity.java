package de.bulling.barcodebuddyscanner;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.ReceiptPdf;

/**
 * Die Artikel eines gesendeten Einkaufs mit der Antwort von Barcode Buddy.
 */
public class TripDetailActivity extends AppCompatActivity {

	public static final String EXTRA_TRIP_ID = "trip_id";

	private ShoppingRepository repo;
	private long               tripId;
	private List<ShoppingItem> items = new ArrayList<>();
	private LineAdapter        adapter;
	private String             summary = "";
	private long               sentAt;

	private final ActivityResultLauncher<String> receiptLauncher = registerForActivityResult(
			new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
				if (uri != null) {
					writeReceipt(uri);
				}
			});

	private void writeReceipt(Uri uri) {
		try (OutputStream out = getContentResolver().openOutputStream(uri)) {
			ReceiptPdf.write(this, out, items, sentAt);
			Toast.makeText(this, R.string.receipt_saved, Toast.LENGTH_LONG).show();
		} catch (IOException | RuntimeException e) {
			Toast.makeText(this, R.string.receipt_failed, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_trip_detail);
		repo = ShoppingRepository.get(this);
		tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, -1);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		adapter = new LineAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
		repo.getTrip(tripId, trip -> {
			if (trip == null) {
				Toast.makeText(this, R.string.trip_not_found, Toast.LENGTH_SHORT).show();
				finish();
				return;
			}
			sentAt = trip.sentAt;
			setTitle(dateFormat.format(new Date(trip.sentAt)));
			summary = getString(R.string.trip_summary, trip.itemCount, trip.unitCount);
			updateSummary();
		});
		repo.getTripItems(tripId, result -> {
			items = result;
			adapter.notifyDataSetChanged();
			updateSummary();
		});
	}

	/** Kopfzeile: Anzahl der Artikel und, wenn Preise bekannt sind, die Summe. */
	private void updateSummary() {
		double total = 0;
		double deposit = 0;
		boolean hasPrice = false;
		for (ShoppingItem item : items) {
			if (item.scanPrice != null) {
				total += item.scanPrice * item.quantity;
				hasPrice = true;
			}
			deposit += item.depositTotal();
		}
		total += deposit;
		String text = summary;
		if (hasPrice || deposit > 0) {
			text += " · " + getString(R.string.trip_total, Money.format(total));
		}
		if (deposit > 0) {
			text += " · " + getString(R.string.total_deposit, Money.format(deposit));
		}
		((TextView) findViewById(R.id.trip_summary)).setText(text);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.trip_detail, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
			return true;
		} else if (id == R.id.action_receipt) {
			if (items.isEmpty()) {
				Toast.makeText(this, R.string.receipt_empty, Toast.LENGTH_SHORT).show();
			} else {
				receiptLauncher.launch("Einkaufsbeleg_"
						+ new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(new Date(sentAt)) + ".pdf");
			}
			return true;
		} else if (id == R.id.action_delete_trip) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.delete_trip_title)
					.setMessage(R.string.delete_trip_message)
					.setPositiveButton(R.string.delete, (d, w) -> {
						repo.deleteTrip(tripId);
						finish();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private class LineAdapter extends RecyclerView.Adapter<LineAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_trip_line, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			ShoppingItem item = items.get(position);
			holder.title.setText(item.name != null ? item.name : item.barcode);
			StringBuilder subtitle = new StringBuilder();
			if (item.name != null) {
				subtitle.append(item.barcode);
			}
			if (item.message != null) {
				if (subtitle.length() > 0) {
					subtitle.append("\n");
				}
				subtitle.append(item.message);
			}
			if (item.deposit != null) {
				if (subtitle.length() > 0) {
					subtitle.append("\n");
				}
				subtitle.append(getString(R.string.trip_item_deposit, Money.format(item.depositTotal())));
			}
			holder.subtitle.setText(subtitle);
			holder.subtitle.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);
			holder.quantity.setText(item.scanPrice == null
					? getString(R.string.trip_item_quantity, item.quantity)
					: getString(R.string.trip_item_price, item.quantity, Money.format(item.scanPrice * item.quantity + item.depositTotal())));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView title;
			final TextView subtitle;
			final TextView quantity;

			ViewHolder(View view) {
				super(view);
				title    = view.findViewById(R.id.line_title);
				subtitle = view.findViewById(R.id.line_subtitle);
				quantity = view.findViewById(R.id.line_quantity);
			}
		}
	}
}

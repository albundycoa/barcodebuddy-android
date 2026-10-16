package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.Trip;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;

/**
 * Liste der bereits gesendeten Einkäufe.
 */
public class HistoryActivity extends AppCompatActivity implements ShoppingRepository.Listener {

	private ShoppingRepository repo;
	private TripAdapter        adapter;
	private TextView           emptyView;
	private List<Trip>         trips = new ArrayList<>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_history);
		repo = ShoppingRepository.get(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		emptyView = findViewById(R.id.empty_view);
		adapter = new TripAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
	}

	@Override
	protected void onStart() {
		super.onStart();
		repo.addListener(this);
		refresh();
	}

	@Override
	protected void onStop() {
		super.onStop();
		repo.removeListener(this);
	}

	@Override
	public void onDataChanged() {
		refresh();
	}

	private void refresh() {
		repo.getTrips(result -> {
			trips = result;
			adapter.notifyDataSetChanged();
			emptyView.setVisibility(trips.isEmpty() ? View.VISIBLE : View.GONE);
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.history, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
			return true;
		} else if (id == R.id.action_clear_history) {
			if (!trips.isEmpty()) {
				new MaterialAlertDialogBuilder(this)
						.setTitle(R.string.clear_history_title)
						.setMessage(R.string.clear_history_message)
						.setPositiveButton(R.string.delete, (d, w) -> repo.clearHistory())
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void confirmDelete(final Trip trip) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.delete_trip_title)
				.setMessage(R.string.delete_trip_message)
				.setPositiveButton(R.string.delete, (d, w) -> repo.deleteTrip(trip.id))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private class TripAdapter extends RecyclerView.Adapter<TripAdapter.ViewHolder> {

		private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_trip, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			final Trip trip = trips.get(position);
			holder.date.setText(dateFormat.format(new Date(trip.sentAt)));
			holder.info.setText(getString(R.string.trip_summary, trip.itemCount, trip.unitCount));
			holder.status.setText(trip.complete ? R.string.trip_complete : R.string.trip_partial);
			holder.itemView.setOnClickListener(v -> startActivity(
					new Intent(HistoryActivity.this, TripDetailActivity.class)
							.putExtra(TripDetailActivity.EXTRA_TRIP_ID, trip.id)));
			holder.delete.setOnClickListener(v -> confirmDelete(trip));
		}

		@Override
		public int getItemCount() {
			return trips.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView date;
			final TextView info;
			final TextView status;
			final View     delete;

			ViewHolder(View view) {
				super(view);
				date   = view.findViewById(R.id.trip_date);
				info   = view.findViewById(R.id.trip_info);
				status = view.findViewById(R.id.trip_status);
				delete = view.findViewById(R.id.trip_delete);
			}
		}
	}
}

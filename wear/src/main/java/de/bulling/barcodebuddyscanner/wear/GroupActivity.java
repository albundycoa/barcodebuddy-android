package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.widget.WearableRecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Produktgruppe für einen freien Eintrag wählen (in der Reihenfolge des Handys), damit er richtig einsortiert wird. */
public class GroupActivity extends Activity {

	private final List<String> groups = new ArrayList<>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list);
		final String name = getIntent().getStringExtra(AmountActivity.EXTRA_NAME);
		TextView empty = findViewById(R.id.empty);
		empty.setVisibility(View.GONE);
		JsonArray array = State.array(State.loadCatalog(this), "g");
		groups.add("");
		for (int i = 0; i < array.size(); i++) {
			groups.add(array.get(i).getAsString());
		}
		WearableRecyclerView list = findViewById(R.id.list);
		list.setEdgeItemsCenteringEnabled(false);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stock, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				final String group = groups.get(position);
				TextView title = holder.itemView.findViewById(R.id.name);
				TextView sub = holder.itemView.findViewById(R.id.sub);
				title.setText(group.isEmpty() ? getString(R.string.group_none) : group);
				sub.setVisibility(View.GONE);
				holder.itemView.setOnClickListener(v -> startActivity(new Intent(GroupActivity.this, AmountActivity.class)
						.putExtra(AmountActivity.EXTRA_NAME, name)
						.putExtra(AmountActivity.EXTRA_GROUP, group)));
			}

			@Override
			public int getItemCount() {
				return groups.size();
			}
		});
		setTitle(getString(R.string.group_title));
	}
}

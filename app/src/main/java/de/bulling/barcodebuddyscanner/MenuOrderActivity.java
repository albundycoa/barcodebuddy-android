package de.bulling.barcodebuddyscanner;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.MenuLayout;

/**
 * Reihenfolge der Einträge im Drei-Punkte-Menü: Am Griff ziehen zum Verschieben, Haken entfernen zum Ausblenden.
 */
public class MenuOrderActivity extends AppCompatActivity {

	private final List<MenuLayout.Entry> entries = new ArrayList<>();
	private final Set<String>            hidden  = new HashSet<>();
	private Adapter                      adapter;
	private ItemTouchHelper              touchHelper;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_menu_order);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		adapter = new Adapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
			@Override
			public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
								  @NonNull RecyclerView.ViewHolder target) {
				int from = viewHolder.getBindingAdapterPosition();
				int to = target.getBindingAdapterPosition();
				Collections.swap(entries, from, to);
				adapter.notifyItemMoved(from, to);
				return true;
			}

			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
			}

			@Override
			public boolean isLongPressDragEnabled() {
				return false;
			}

			@Override
			public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
				super.clearView(recyclerView, viewHolder);
				save();
			}
		});
		touchHelper.attachToRecyclerView(list);
		load();
	}

	private void load() {
		entries.clear();
		entries.addAll(MenuLayout.orderedEntries(this));
		hidden.clear();
		hidden.addAll(MenuLayout.hidden(this));
		adapter.notifyDataSetChanged();
	}

	private void save() {
		List<String> order = new ArrayList<>();
		for (MenuLayout.Entry e : entries) {
			order.add(e.key);
		}
		MenuLayout.save(this, order, hidden);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_order_reset).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		} else if (item.getItemId() == 1) {
			MenuLayout.reset(this);
			load();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_menu_order, parent, false));
		}

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			final MenuLayout.Entry entry = entries.get(position);
			holder.name.setText(entry.title);
			boolean mandatory = MenuLayout.isMandatory(entry.key);
			holder.visible.setOnCheckedChangeListener(null);
			holder.visible.setChecked(mandatory || !hidden.contains(entry.key));
			holder.visible.setEnabled(!mandatory);
			holder.visible.setOnCheckedChangeListener((b, checked) -> {
				if (checked) {
					hidden.remove(entry.key);
				} else {
					hidden.add(entry.key);
				}
				save();
			});
			holder.handle.setOnTouchListener((v, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
					touchHelper.startDrag(holder);
				}
				return false;
			});
		}

		@Override
		public int getItemCount() {
			return entries.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final View     handle;
			final CheckBox visible;

			ViewHolder(View view) {
				super(view);
				name    = view.findViewById(R.id.menu_name);
				handle  = view.findViewById(R.id.menu_handle);
				visible = view.findViewById(R.id.menu_visible);
			}
		}
	}
}

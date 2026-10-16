package de.bulling.barcodebuddyscanner;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingData;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingEntry;
import de.bulling.barcodebuddyscanner.Helper.Money;

/**
 * Einkaufsmodus: Die Einkaufsliste aus Grocy mit großen Karten, nach Geschäften gruppiert. Erledigte
 * Einträge (abgehakt oder durch Scans abgedeckt) stehen durchgestrichen im Abschnitt "Erledigt" ganz unten.
 */
public class ShoppingEntryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	public interface Actions {
		void onToggle(ShoppingEntry entry);
	}

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ENTRY  = 1;

	private static final long GROUP_NONE = 0;

	private static class Row {
		final String        header;
		final ShoppingEntry entry;

		Row(String header, ShoppingEntry entry) {
			this.header = header;
			this.entry  = entry;
		}
	}

	private final Context   context;
	private final Actions   actions;
	private final List<Row> rows = new ArrayList<>();
	private       int       doneCount;
	private       int       totalCount;

	public ShoppingEntryAdapter(Context context, Actions actions) {
		this.context = context.getApplicationContext();
		this.actions = actions;
	}

	public int getDoneCount() {
		return doneCount;
	}

	public int getTotalCount() {
		return totalCount;
	}

	public void submit(ShoppingData data) {
		rows.clear();
		doneCount = 0;
		totalCount = data.entries.size();

		final Map<Long, List<ShoppingEntry>> groups = new HashMap<>();
		final Map<Long, String> shopNames = new HashMap<>();
		List<ShoppingEntry> done = new ArrayList<>();
		for (ShoppingEntry e : data.entries) {
			if (e.isDone()) {
				done.add(e);
				doneCount++;
				continue;
			}
			long key = e.shopId != null && !e.isNote ? e.shopId : GROUP_NONE;
			List<ShoppingEntry> group = groups.get(key);
			if (group == null) {
				group = new ArrayList<>();
				groups.put(key, group);
			}
			group.add(e);
			if (key > 0) {
				shopNames.put(key, e.shopName);
			}
		}

		final Map<Long, Integer> positions = data.shopPosition;
		List<Long> keys = new ArrayList<>(groups.keySet());
		Collections.sort(keys, (a, b) -> {
			int ra = a == GROUP_NONE ? 1 : 0, rb = b == GROUP_NONE ? 1 : 0;
			if (ra != rb) {
				return ra < rb ? -1 : 1;
			}
			int pa = positions.containsKey(a) ? positions.get(a) : Integer.MAX_VALUE;
			int pb = positions.containsKey(b) ? positions.get(b) : Integer.MAX_VALUE;
			if (pa != pb) {
				return pa < pb ? -1 : 1;
			}
			return String.valueOf(shopNames.get(a)).compareToIgnoreCase(String.valueOf(shopNames.get(b)));
		});
		for (Long key : keys) {
			List<ShoppingEntry> group = groups.get(key);
			Collections.sort(group, (a, b) -> a.name.compareToIgnoreCase(b.name));
			String title = key == GROUP_NONE ? context.getString(R.string.section_none) : shopNames.get(key);
			rows.add(new Row(context.getString(R.string.section_title, title, group.size()), null));
			for (ShoppingEntry e : group) {
				rows.add(new Row(null, e));
			}
		}
		if (!done.isEmpty()) {
			Collections.sort(done, (a, b) -> a.name.compareToIgnoreCase(b.name));
			rows.add(new Row(context.getString(R.string.section_title, context.getString(R.string.section_done), done.size()), null));
			for (ShoppingEntry e : done) {
				rows.add(new Row(null, e));
			}
		}
		notifyDataSetChanged();
	}

	@Override
	public int getItemViewType(int position) {
		return rows.get(position).entry == null ? TYPE_HEADER : TYPE_ENTRY;
	}

	@Override
	public int getItemCount() {
		return rows.size();
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == TYPE_HEADER) {
			return new HeaderHolder(inflater.inflate(R.layout.item_section, parent, false));
		}
		return new EntryHolder(inflater.inflate(R.layout.item_entry, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
		Row row = rows.get(position);
		if (row.entry == null) {
			((HeaderHolder) viewHolder).title.setText(row.header);
			return;
		}
		final ShoppingEntry e = row.entry;
		EntryHolder h = (EntryHolder) viewHolder;
		boolean done = e.isDone();

		h.title.setText(e.name);
		h.title.setPaintFlags(done ? h.title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
				: h.title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);

		String unit = e.unit == null ? "" : " " + e.unit;
		h.amount.setText(trim(e.amount) + unit);
		h.amount.setPaintFlags(done ? h.amount.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
				: h.amount.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);

		// Letzter Preis je Einheit des Eintrags (die Menge im Eintrag und in Lagereinheiten ergeben den Faktor)
		if (e.lastPrice != null && e.amount > 0) {
			double perEntryUnit = e.lastPrice * e.stockAmount / e.amount;
			h.price.setText(context.getString(R.string.entry_price, Money.format(perEntryUnit)));
			h.price.setVisibility(View.VISIBLE);
		} else {
			h.price.setVisibility(View.GONE);
		}

		String info = e.group == null ? "" : e.group;
		if (e.scanned > 0 && !e.isNote) {
			String scanned = context.getString(R.string.entry_scanned, trim(Math.min(e.scanned, e.stockAmount)),
					trim(e.stockAmount), e.stockUnit == null ? "" : e.stockUnit).trim();
			info = info.isEmpty() ? scanned : info + " · " + scanned;
		}
		h.info.setText(info);
		h.info.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);

		h.check.setOnCheckedChangeListener(null);
		h.check.setChecked(done);
		h.itemView.setAlpha(done ? 0.55f : 1f);
		View.OnClickListener toggle = v -> actions.onToggle(e);
		h.itemView.setOnClickListener(toggle);
		h.check.setOnClickListener(toggle);
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
	}

	static class HeaderHolder extends RecyclerView.ViewHolder {
		final TextView title;

		HeaderHolder(View view) {
			super(view);
			title = view.findViewById(R.id.section_title);
		}
	}

	static class EntryHolder extends RecyclerView.ViewHolder {
		final TextView         title;
		final TextView         amount;
		final TextView         price;
		final TextView         info;
		final MaterialCheckBox check;

		EntryHolder(View view) {
			super(view);
			title  = view.findViewById(R.id.entry_title);
			amount = view.findViewById(R.id.entry_amount);
			price  = view.findViewById(R.id.entry_price);
			info   = view.findViewById(R.id.entry_info);
			check  = view.findViewById(R.id.entry_check);
		}
	}
}

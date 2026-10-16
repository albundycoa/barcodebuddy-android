package de.bulling.barcodebuddyscanner;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.Money;

/**
 * Zeigt die offene Einkaufsliste, nach Geschäften gruppiert, mit Stückzahl- und Preis-Steuerung.
 * Oben stehen Produkte, die Grocy nicht kennt, danach die Geschäfte in der vom Nutzer
 * gewählten Reihenfolge und zuletzt Produkte ohne Geschäft.
 */
public class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	public interface Actions {
		void onIncrease(ShoppingItem item);

		void onDecrease(ShoppingItem item);

		void onEditQuantity(ShoppingItem item);

		void onEditPrice(ShoppingItem item);

		void onDelete(ShoppingItem item);
	}

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM   = 1;

	private static final long GROUP_NEW  = -1;
	private static final long GROUP_NONE = 0;

	private static class Row {
		final String       header;
		final ShoppingItem item;

		Row(String header, ShoppingItem item) {
			this.header = header;
			this.item   = item;
		}
	}

	private final Context    context;
	private final Actions    actions;
	private final List<Row>  rows     = new ArrayList<>();
	private       boolean    editable = true;

	public ItemAdapter(Context context, Actions actions) {
		this.context = context.getApplicationContext();
		this.actions = actions;
	}

	public void submit(ShoppingRepository.OpenList list) {
		rows.clear();
		if (!list.cacheAvailable) {
			// Ohne Grocy-Daten lässt sich nicht sagen, was neu ist und wo es gekauft wird
			for (ShoppingItem item : list.items) {
				rows.add(new Row(null, item));
			}
		} else {
			final Map<Long, List<ShoppingItem>> groups = new HashMap<>();
			final Map<Long, String> shopNames = new HashMap<>();
			for (ShoppingItem item : list.items) {
				long key = !item.known ? GROUP_NEW : (item.shopId != null ? item.shopId : GROUP_NONE);
				List<ShoppingItem> group = groups.get(key);
				if (group == null) {
					group = new ArrayList<>();
					groups.put(key, group);
				}
				group.add(item);
				if (key > 0) {
					shopNames.put(key, item.shopName);
				}
			}
			final Map<Long, Integer> positions = list.shopPosition;
			List<Long> keys = new ArrayList<>(groups.keySet());
			Collections.sort(keys, (a, b) -> {
				int ra = rank(a), rb = rank(b);
				if (ra != rb) {
					return ra < rb ? -1 : 1;
				}
				if (ra != 1) {
					return 0;
				}
				int pa = positions.containsKey(a) ? positions.get(a) : Integer.MAX_VALUE;
				int pb = positions.containsKey(b) ? positions.get(b) : Integer.MAX_VALUE;
				if (pa != pb) {
					return pa < pb ? -1 : 1;
				}
				return String.valueOf(shopNames.get(a)).compareToIgnoreCase(String.valueOf(shopNames.get(b)));
			});
			for (Long key : keys) {
				List<ShoppingItem> group = groups.get(key);
				Collections.sort(group, BY_NAME);
				String title = key == GROUP_NEW ? context.getString(R.string.section_new)
						: key == GROUP_NONE ? context.getString(R.string.section_none)
						: shopNames.get(key);
				double sum = 0;
				boolean anyPrice = false;
				for (ShoppingItem item : group) {
					Double scan = item.effectiveScanPrice();
					if (scan != null) {
						sum += scan * item.quantity;
						anyPrice = true;
					}
				}
				rows.add(new Row(anyPrice
						? context.getString(R.string.section_title_sum, title, group.size(), Money.format(sum))
						: context.getString(R.string.section_title, title, group.size()), null));
				for (ShoppingItem item : group) {
					rows.add(new Row(null, item));
				}
			}
		}
		notifyDataSetChanged();
	}

	private static int rank(long key) {
		return key == GROUP_NEW ? 0 : key == GROUP_NONE ? 2 : 1;
	}

	private static final Comparator<ShoppingItem> BY_NAME = (a, b) -> {
		String na = a.displayName() != null ? a.displayName() : a.barcode;
		String nb = b.displayName() != null ? b.displayName() : b.barcode;
		return na.compareToIgnoreCase(nb);
	};

	/** Während des Sendens darf die Liste nicht verändert werden. */
	public void setEditable(boolean editable) {
		if (this.editable != editable) {
			this.editable = editable;
			notifyDataSetChanged();
		}
	}

	/** null, wenn an dieser Stelle eine Abschnittsüberschrift steht. */
	public ShoppingItem getItem(int position) {
		return rows.get(position).item;
	}

	@Override
	public int getItemViewType(int position) {
		return rows.get(position).item == null ? TYPE_HEADER : TYPE_ITEM;
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
		return new ItemHolder(inflater.inflate(R.layout.item_shopping, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
		Row row = rows.get(position);
		if (row.item == null) {
			((HeaderHolder) viewHolder).title.setText(row.header);
			return;
		}
		final ShoppingItem item = row.item;
		ItemHolder holder = (ItemHolder) viewHolder;

		holder.title.setText(item.displayName() != null ? item.displayName() : item.barcode);

		StringBuilder subtitle = new StringBuilder();
		if (item.displayName() != null) {
			subtitle.append(item.barcode);
		}
		if (item.known && item.stockPerScan > 1 && item.stockUnitName != null) {
			append(subtitle, context.getString(R.string.item_per_scan, trim(item.stockPerScan), item.stockUnitName));
		}
		if (item.known && item.stock != null && item.stockUnitName != null) {
			append(subtitle, context.getString(R.string.item_stock, trim(item.stock), item.stockUnitName));
		}
		if (item.sentCount > 0 && item.sentCount < item.quantity) {
			append(subtitle, context.getString(R.string.item_partly_sent, item.sentCount));
		}
		holder.subtitle.setText(subtitle);
		holder.subtitle.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);

		Double price = item.effectivePrice();
		if (price == null) {
			holder.price.setText(R.string.price_add);
		} else {
			String unit = item.priceUnitName != null ? item.priceUnitName : context.getString(R.string.price_unit_scan);
			holder.price.setText(context.getString(R.string.price_line, Money.format(price), unit,
					Money.format(item.effectiveScanPrice() * item.quantity)));
		}

		holder.error.setText(item.error);
		holder.error.setVisibility(item.error != null ? View.VISIBLE : View.GONE);

		holder.quantity.setText(String.valueOf(item.quantity));

		holder.minus.setEnabled(editable && item.quantity > 1);
		holder.plus.setEnabled(editable);
		holder.delete.setEnabled(editable);
		holder.quantity.setEnabled(editable);
		holder.price.setEnabled(editable);

		holder.minus.setOnClickListener(v -> actions.onDecrease(item));
		holder.plus.setOnClickListener(v -> actions.onIncrease(item));
		holder.delete.setOnClickListener(v -> actions.onDelete(item));
		holder.quantity.setOnClickListener(v -> actions.onEditQuantity(item));
		holder.price.setOnClickListener(v -> actions.onEditPrice(item));
	}

	private static void append(StringBuilder builder, String text) {
		if (builder.length() > 0) {
			builder.append(" · ");
		}
		builder.append(text);
	}

	/** 8.0 -> "8", 0.5 -> "0,5" */
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

	static class ItemHolder extends RecyclerView.ViewHolder {
		final TextView title;
		final TextView subtitle;
		final TextView price;
		final TextView error;
		final TextView quantity;
		final View     minus;
		final View     plus;
		final View     delete;

		ItemHolder(View view) {
			super(view);
			title    = view.findViewById(R.id.item_title);
			subtitle = view.findViewById(R.id.item_subtitle);
			price    = view.findViewById(R.id.item_price);
			error    = view.findViewById(R.id.item_error);
			quantity = view.findViewById(R.id.item_quantity);
			minus    = view.findViewById(R.id.item_minus);
			plus     = view.findViewById(R.id.item_plus);
			delete   = view.findViewById(R.id.item_delete);
		}
	}
}

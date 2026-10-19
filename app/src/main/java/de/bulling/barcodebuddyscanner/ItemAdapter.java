package de.bulling.barcodebuddyscanner;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.Money;

/**
 * Zeigt die gescannten Artikel in Scanreihenfolge (neuester oben) mit Stückzahl-, Preis- und Geschäfts-Steuerung.
 */
public class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	public interface Actions {
		void onIncrease(ShoppingItem item);

		void onDecrease(ShoppingItem item);

		void onEditQuantity(ShoppingItem item);

		void onEditPrice(ShoppingItem item);

		void onDelete(ShoppingItem item);

		/** Unbekannten Barcode einem Produkt zuordnen oder dafür ein neues Produkt anlegen. */
		void onAssign(ShoppingItem item);

		/** Geschäft des Barcodes ändern (wird in Grocy am Barcode gespeichert). */
		void onChangeShop(ShoppingItem item);
	}

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM   = 1;

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
	private       boolean    cacheAvailable;

	public ItemAdapter(Context context, Actions actions) {
		this.context = context.getApplicationContext();
		this.actions = actions;
	}

	public void submit(ShoppingRepository.OpenList list) {
		rows.clear();
		cacheAvailable = list.cacheAvailable;
		// In Scanreihenfolge: der neueste Scan steht oben (die Liste kommt bereits so sortiert aus der Datenbank)
		for (ShoppingItem item : list.items) {
			rows.add(new Row(null, item));
		}
		notifyDataSetChanged();
	}

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

		boolean suggested = !item.known && item.displayName() == null && item.suggestion != null;
		holder.title.setText(item.displayName() != null ? item.displayName() : suggested ? item.suggestion : item.barcode);

		StringBuilder subtitle = new StringBuilder();
		if (suggested) {
			subtitle.append(context.getString(R.string.item_unknown_suggestion)).append(" · ").append(item.barcode);
		} else if (item.displayName() != null) {
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
		if (item.deposit != null) {
			append(subtitle, context.getString(R.string.item_deposit_line, item.quantity, Money.format(item.deposit),
					Money.format(item.depositTotal())));
		}
		holder.subtitle.setText(subtitle);
		holder.subtitle.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);

		// Geschäft des Barcodes: Tippen ändert es (falsch zugeordnet, z. B. Aldi statt Lidl)
		if (item.known && cacheAvailable) {
			holder.shop.setVisibility(View.VISIBLE);
			holder.shop.setText(item.shopName != null ? context.getString(R.string.item_shop_label, item.shopName)
					: context.getString(R.string.item_shop_none));
			holder.shop.setEnabled(editable);
			holder.shop.setOnClickListener(v -> actions.onChangeShop(item));
		} else {
			holder.shop.setVisibility(View.GONE);
		}

		Double price = item.effectivePrice();
		if (price == null) {
			holder.price.setText(R.string.price_add);
		} else {
			String unit = item.priceUnitName != null ? item.priceUnitName : context.getString(R.string.price_unit_scan);
			holder.price.setText(context.getString(R.string.price_line, Money.format(price), unit,
					Money.format(item.effectiveScanPrice() * item.quantity)));
		}

		// Unbekannter Barcode: Produkt anlegen oder einem vorhandenen zuordnen
		holder.assign.setVisibility(!item.known && cacheAvailable ? View.VISIBLE : View.GONE);
		holder.assign.setEnabled(editable);
		holder.assign.setOnClickListener(v -> actions.onAssign(item));

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
		final TextView shop;
		final TextView price;
		final TextView error;
		final TextView quantity;
		final View     minus;
		final View     plus;
		final View     delete;
		final View     assign;

		ItemHolder(View view) {
			super(view);
			title    = view.findViewById(R.id.item_title);
			subtitle = view.findViewById(R.id.item_subtitle);
			shop     = view.findViewById(R.id.item_shop);
			price    = view.findViewById(R.id.item_price);
			error    = view.findViewById(R.id.item_error);
			quantity = view.findViewById(R.id.item_quantity);
			minus    = view.findViewById(R.id.item_minus);
			plus     = view.findViewById(R.id.item_plus);
			delete   = view.findViewById(R.id.item_delete);
			assign   = view.findViewById(R.id.item_assign);
		}
	}
}

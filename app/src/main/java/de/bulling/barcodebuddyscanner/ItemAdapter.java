package de.bulling.barcodebuddyscanner;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;

/**
 * Zeigt die offene Einkaufsliste mit Stückzahl-Steuerung.
 */
public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ViewHolder> {

	public interface Actions {
		void onIncrease(ShoppingItem item);

		void onDecrease(ShoppingItem item);

		void onEditQuantity(ShoppingItem item);

		void onDelete(ShoppingItem item);
	}

	private final Actions            actions;
	private final List<ShoppingItem> items    = new ArrayList<>();
	private       boolean            editable = true;

	public ItemAdapter(Actions actions) {
		this.actions = actions;
	}

	public void submit(List<ShoppingItem> newItems) {
		items.clear();
		items.addAll(newItems);
		notifyDataSetChanged();
	}

	/** Während des Sendens darf die Liste nicht verändert werden. */
	public void setEditable(boolean editable) {
		if (this.editable != editable) {
			this.editable = editable;
			notifyDataSetChanged();
		}
	}

	public ShoppingItem getItem(int position) {
		return items.get(position);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shopping, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		final ShoppingItem item    = items.get(position);
		final android.content.Context context = holder.itemView.getContext();

		holder.title.setText(item.name != null ? item.name : item.barcode);

		StringBuilder subtitle = new StringBuilder();
		if (item.name != null) {
			subtitle.append(item.barcode);
		}
		if (item.sentCount > 0 && item.sentCount < item.quantity) {
			if (subtitle.length() > 0) {
				subtitle.append(" · ");
			}
			subtitle.append(context.getString(R.string.item_partly_sent, item.sentCount));
		}
		holder.subtitle.setText(subtitle);
		holder.subtitle.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);

		holder.error.setText(item.error);
		holder.error.setVisibility(item.error != null ? View.VISIBLE : View.GONE);

		holder.quantity.setText(String.valueOf(item.quantity));

		holder.minus.setEnabled(editable && item.quantity > 1);
		holder.plus.setEnabled(editable);
		holder.delete.setEnabled(editable);
		holder.quantity.setEnabled(editable);

		holder.minus.setOnClickListener(v -> actions.onDecrease(item));
		holder.plus.setOnClickListener(v -> actions.onIncrease(item));
		holder.delete.setOnClickListener(v -> actions.onDelete(item));
		holder.quantity.setOnClickListener(v -> actions.onEditQuantity(item));
	}

	static class ViewHolder extends RecyclerView.ViewHolder {
		final TextView title;
		final TextView subtitle;
		final TextView error;
		final TextView quantity;
		final View     minus;
		final View     plus;
		final View     delete;

		ViewHolder(View view) {
			super(view);
			title    = view.findViewById(R.id.item_title);
			subtitle = view.findViewById(R.id.item_subtitle);
			error    = view.findViewById(R.id.item_error);
			quantity = view.findViewById(R.id.item_quantity);
			minus    = view.findViewById(R.id.item_minus);
			plus     = view.findViewById(R.id.item_plus);
			delete   = view.findViewById(R.id.item_delete);
		}
	}
}

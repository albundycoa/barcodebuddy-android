package de.bulling.barcodebuddyscanner.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Nimmt den Stand vom Handy entgegen, auch wenn die Uhr-App gerade nicht geöffnet ist. */
public class StateService extends WearableListenerService {

	@Override
	public void onDataChanged(DataEventBuffer events) {
		for (DataEvent event : events) {
			if (event.getType() != DataEvent.TYPE_CHANGED) {
				continue;
			}
			String path = event.getDataItem().getUri().getPath();
			boolean stock = "/bbs/stock".equals(path);
			boolean catalog = "/bbs/catalog".equals(path);
			if (stock || catalog || "/bbs/state".equals(path)) {
				String json = DataMapItem.fromDataItem(event.getDataItem()).getDataMap().getString("json");
				if (json != null) {
					if (catalog) {
						State.saveCatalog(getApplicationContext(), json);
					} else if (stock) {
						State.saveStock(getApplicationContext(), json);
					} else {
						State.save(getApplicationContext(), json);
					}
				}
			}
		}
	}
}

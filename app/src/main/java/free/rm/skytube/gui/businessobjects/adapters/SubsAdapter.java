/*
 * SkyTube
 * Copyright (C) 2018  Ramon Mifsud
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package free.rm.skytube.gui.businessobjects.adapters;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import free.rm.skytube.R;
import free.rm.skytube.app.EventBus;
import free.rm.skytube.businessobjects.YouTube.POJOs.ChannelView;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.businessobjects.db.DatabaseTasks;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;
import free.rm.skytube.databinding.SubChannelBinding;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Channel subscriptions adapter: Contains a list of channels (that the user subscribed to) together
 * with a notification whether the channel has new videos since last visit to the channel or not.
 */
public class SubsAdapter extends RecyclerViewAdapterEx<ChannelView, SubsAdapter.SubChannelViewHolder> {

	private static final String TAG = SubsAdapter.class.getSimpleName();

	private String searchText;
	private Runnable moveFocusToVideos;

	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	public SubsAdapter(Context context, View progressBar) {
		super(context);

		// populate this adapter with user's subscribed channels
		executeQuery(null, progressBar);
	}

	/**
	 * Remove channel from this adapter.
	 *
	 * @param channelId Channel to remove.
	 */
	public void removeChannel(ChannelId channelId) {
		int size = getItemCount();

		for (int i = 0; i < size; i++) {
			if (get(i).getId().equals(channelId)) {
				remove(i);
				return;
			}
		}

		Log.e(TAG, "Channel not removed from adapter:  id=" + channelId);
	}

	/**
	 * Changes the channel's 'new videos' status.  The channel's view is then refreshed.
	 *
	 * @param channelId Channel ID.
	 * @param newVideos 'New videos' status (true = new videos have been added since user's last
	 *                  visit;  false = no new videos)
	 * @return True if the operations have been successful; false otherwise.
	 */
	public boolean changeChannelNewVideosStatus(ChannelId channelId, boolean newVideos) {
		ChannelView channel;
		int position = 0;

		for (Iterator<ChannelView> i = getIterator(); i.hasNext(); position++) {
			channel = i.next();

			if (channel.getId() != null && channel.getId().equals(channelId)) {
				// change the 'new videos' status
				channel.setNewVideosSinceLastVisit(newVideos);
				// we now need to notify the SubsAdapter to remove the new videos notification (near the channel name)
				updateView(position);
				return true;
			}
		}

		return false;
	}

	/**
	 * Update the contents of a view (i.e. refreshes the given view).
	 *
	 * @param viewPosition The position of the view that we want to update.
	 */
	private void updateView(int viewPosition) {
		notifyItemChanged(viewPosition);
	}

	@Override
	@NonNull
	public SubChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		SubChannelBinding binding = SubChannelBinding.inflate(LayoutInflater.from(parent.getContext()),
				parent, false);
		return new SubChannelViewHolder(binding);
	}

	@Override
	public void onBindViewHolder(SubChannelViewHolder viewHolder, int position) {
		viewHolder.updateInfo(get(position));
	}

	/**
	 * This should be called only from MainFragment
	 */
	public void refreshSubsList() {
		clearList();
		executeQuery(searchText, null);
	}

	private void refreshFilteredSubsList(String searchText) {
		clearList();
		executeQuery(searchText, null);
	}

	private void executeQuery(String searchText, View progressBar) {
		compositeDisposable.add(DatabaseTasks.getSubscribedChannelView(getContext(), progressBar, searchText)
				.subscribe(channels -> {
					List<ChannelView> displayed = new ArrayList<>(channels);
					ChannelView bilibili = BilibiliService.get().createInitialChannelView();
					boolean matches = searchText == null || searchText.trim().isEmpty()
							|| bilibili.getTitle().toLowerCase(Locale.ROOT)
							.contains(searchText.trim().toLowerCase(Locale.ROOT));
					boolean duplicate = false;
					for (ChannelView channel : displayed) {
						if (channel.getId().equals(bilibili.getId())) {
							duplicate = true;
							break;
						}
					}
					if (matches && !duplicate) {
						displayed.add(0, bilibili);
					}
					appendList(displayed);
				}));
	}

	public void filterSubSearch(String searchText){
		this.searchText = searchText;
		refreshFilteredSubsList(searchText);
	}

	public void setMoveFocusToVideos(Runnable moveFocusToVideos) {
		this.moveFocusToVideos = moveFocusToVideos;
	}

	////////////////////////////////////////////////////////////////////////////////////////////////

	class SubChannelViewHolder extends RecyclerView.ViewHolder {
		private final SubChannelBinding binding;
		private ChannelView channel;

		SubChannelViewHolder(SubChannelBinding binding) {
			super(binding.getRoot());
			this.binding = binding;
			binding.getRoot().setOnClickListener(v -> {
				ChannelId channelId = channel.getId();
				EventBus.getInstance().notifyMainActivities(listener -> {
					listener.onChannelClick(channelId);
				});
			});
			binding.getRoot().setOnFocusChangeListener((view, hasFocus) ->
					binding.subChannelNameTextView.setSelected(hasFocus));
			binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
				if (event.getAction() != KeyEvent.ACTION_DOWN) {
					return false;
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
					return moveSubscriptionFocus(view, -1);
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
					return moveSubscriptionFocus(view, 1);
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && moveFocusToVideos != null) {
					moveFocusToVideos.run();
					return true;
				}
				if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER
						|| keyCode == KeyEvent.KEYCODE_ENTER
						|| keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
						&& event.getRepeatCount() == 0) {
					view.performClick();
					return true;
				}
				return false;
			});
		}

		private boolean moveSubscriptionFocus(View current, int offset) {
			if (!(current.getParent() instanceof RecyclerView)) {
				return false;
			}
			RecyclerView list = (RecyclerView) current.getParent();
			int currentPosition = list.getChildAdapterPosition(current);
			int targetPosition = currentPosition + offset;
			if (currentPosition == RecyclerView.NO_POSITION
					|| targetPosition < 0
					|| targetPosition >= getItemCount()) {
				return false;
			}

			RecyclerView.ViewHolder target = list.findViewHolderForAdapterPosition(targetPosition);
			if (target != null) {
				return target.itemView.requestFocus();
			}

			list.scrollToPosition(targetPosition);
			list.post(() -> {
				RecyclerView.ViewHolder scrolledTarget =
						list.findViewHolderForAdapterPosition(targetPosition);
				if (scrolledTarget != null) {
					scrolledTarget.itemView.requestFocus();
				}
			});
			return true;
		}

		void updateInfo(ChannelView channel) {
			Glide.with(itemView.getContext().getApplicationContext())
					.load(channel.getThumbnailUrl())
					.apply(new RequestOptions().placeholder(R.drawable.channel_thumbnail_default))
					.into(binding.subChannelThumbnailImageView);

            final String prefix;
            final boolean isBilibili = BilibiliService.isChannelId(
                    channel.getId().getRawId());
            if (isBilibili) {
                prefix = "";
            } else {
                switch (channel.status()) {
                    case ACCOUNT_TERMINATED: {
                        prefix = itemView.getContext().getString(R.string.status_account_terminated);
                        break;
                    }
                    case NOT_EXISTS: {
                        prefix = itemView.getContext().getString(R.string.status_not_exists);
                        break;
                    }
                    default: {
                        prefix = "";
                        break;
                    }
                }
            }
			String displayTitle = channel.getTitle() == null ? "" : channel.getTitle();
			if (isBilibili && !displayTitle.startsWith("Bili | ")) {
				displayTitle = "Bili | " + displayTitle;
			}
			binding.subChannelNameTextView.setText(prefix + displayTitle);
			binding.subChannelNewVideosNotification.setVisibility(channel.isNewVideosSinceLastVisit() ? View.VISIBLE : View.INVISIBLE);
			this.channel = channel;
		}
	}
}

package free.rm.skytube.gui.businessobjects.adapters;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import free.rm.skytube.R;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb.PlaybackHistoryEntry;
import free.rm.skytube.databinding.PlaybackHistoryItemBinding;
import free.rm.skytube.gui.businessobjects.YouTubePlayer;

/** Displays playback history as thumbnail/title/time horizontal rows. */
public class PlaybackHistoryAdapter extends
        RecyclerViewAdapterEx<PlaybackHistoryEntry, PlaybackHistoryAdapter.HistoryViewHolder> {

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        setContext(parent.getContext());
        return new HistoryViewHolder(PlaybackHistoryItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        holder.bind(get(position));
    }

    final class HistoryViewHolder extends RecyclerView.ViewHolder {
        private final PlaybackHistoryItemBinding binding;

        HistoryViewHolder(PlaybackHistoryItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(PlaybackHistoryEntry entry) {
            binding.titleTextView.setText(entry.getVideo().getTitle());
            binding.playedTimeTextView.setText(DateUtils.getRelativeTimeSpanString(
                    entry.getPlayedAt(), System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
            Glide.with(binding.thumbnailImageView)
                    .load(entry.getVideo().getThumbnailUrl())
                    .apply(new RequestOptions().placeholder(R.drawable.thumbnail_default))
                    .into(binding.thumbnailImageView);
            binding.getRoot().setOnClickListener(view ->
                    YouTubePlayer.launch(entry.getVideo(), view.getContext()));
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> {
                final float scale = hasFocus ? 1.02f : 1.0f;
                view.animate().scaleX(scale).scaleY(scale).setDuration(100L).start();
                view.setTranslationZ(hasFocus ? 10.0f : 0.0f);
            });
        }
    }
}

package org.moire.ultrasonic.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.moire.ultrasonic.R;
import org.moire.ultrasonic.domain.Lyrics;
import org.moire.ultrasonic.service.MusicService;
import org.moire.ultrasonic.service.MusicServiceFactory;
import org.moire.ultrasonic.util.BackgroundTask;
import org.moire.ultrasonic.util.CancellationToken;
import org.moire.ultrasonic.util.Constants;
import org.moire.ultrasonic.util.FragmentBackgroundTask;
import org.moire.ultrasonic.util.Util;

import timber.log.Timber;

/**
 * Displays the lyrics of a song
 */
public class LyricsFragment extends Fragment {

    private TextView artistView;
    private TextView titleView;
    private TextView textView;
    private SwipeRefreshLayout swipe;
    private CancellationToken cancellationToken;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Util.applyTheme(this.getContext());
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.lyrics, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        cancellationToken = new CancellationToken();
        Timber.d("Lyrics set title");
        FragmentTitle.Companion.setTitle(this, R.string.download_menu_lyrics);

        swipe = view.findViewById(R.id.lyrics_refresh);
        swipe.setEnabled(false);
        artistView = view.findViewById(R.id.lyrics_artist);
        titleView = view.findViewById(R.id.lyrics_title);
        textView = view.findViewById(R.id.lyrics_text);

        load();
    }

    @Override
    public void onDestroyView() {
        cancellationToken.cancel();
        super.onDestroyView();
    }

    private void load()
    {
        BackgroundTask<Lyrics> task = new FragmentBackgroundTask<Lyrics>(getActivity(), true, swipe, cancellationToken)
        {
            @Override
            protected Lyrics doInBackground() throws Throwable
            {
                Bundle arguments = getArguments();
                if (arguments == null) return null;
                String artist = arguments.getString(Constants.INTENT_ARTIST);
                String title = arguments.getString(Constants.INTENT_TITLE);
                MusicService musicService = MusicServiceFactory.getMusicService();
                return musicService.getLyrics(artist, title);
            }

            @Override
            protected void done(Lyrics result)
            {
                if (result != null && result.getArtist() != null)
                {
                    artistView.setText(result.getArtist());
                    titleView.setText(result.getTitle());
                    textView.setText(result.getText());
                }
                else
                {
                    artistView.setText(R.string.lyrics_nomatch);
                }

            }
        };
        task.execute();
    }
}

package com.VocoCraft.VocoCraft;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GameAssets {
    // get game list from assets/games
    public static List<String> listGames(Context context) {
        AssetManager assetManager = context.getAssets();
        List<String> games = new ArrayList<>();
        try {
            String[] files = assetManager.list("games");
            if (files != null) {
                games.addAll(Arrays.asList(files));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return games;
    }
}

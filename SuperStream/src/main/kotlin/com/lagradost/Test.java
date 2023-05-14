package com.lagradost;

import com.lagradost.cloudstream3.TvType;

import java.util.Set;

public class Test {

    public static void main(String[] args) {
        SuperStream superStream  = new SuperStream();
        Set<TvType> tvTypes = superStream.getSupportedTypes();
        System.out.println(tvTypes.size());
    }
}

/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dev.morling.onebrc;

import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class CalculateAverage_PEWorkshop3 {

    /**
     * Parse temperatures as integers. Avoids calling parseFloat which is expensive.
     */
    private static final String FILE_NAME = "./measurements.txt";

    private static Unsafe initUnsafe() {
        try {
            final Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return (Unsafe) theUnsafe.get(Unsafe.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final static Unsafe UNSAFE = initUnsafe();

    private static final class Row {
        private final int minTemp;
        private final int maxTemp;
        private final int count;
        private final int sum;

        public Row(int minTemp, int maxTemp, int count, int sum) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
        }

        public Row(int temperature) {
            this(temperature, temperature, 1, temperature);
        }

        Row update(int temperature) {
            int minTemp = Integer.min(this.minTemp, temperature);
            int maxTemp = Integer.max(this.maxTemp, temperature);
            return new Row(minTemp, maxTemp, this.count + 1, this.sum + temperature);
        }

        @Override
        public String toString() {
            return String.format("%.1f/%.1f/%.1f", (this.minTemp) / 10.0, this.sum / (count * 10.0), (maxTemp) / 10.0);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;
            if (obj == null || obj.getClass() != this.getClass())
                return false;
            var that = (Row) obj;
            return Float.floatToIntBits(this.minTemp) == Float.floatToIntBits(that.minTemp)
                    && Float.floatToIntBits(this.maxTemp) == Float.floatToIntBits(that.maxTemp)
                    && this.count == that.count && Float.floatToIntBits(this.sum) == Float.floatToIntBits(that.sum);
        }

        @Override
        public int hashCode() {
            return Objects.hash(minTemp, maxTemp, count, sum);
        }

    }

    private static final Map<String, Row> records = new HashMap<>();

    private static int parseTemperature(byte[] temperatureBytes, int temperatureBytesLength) {
        int temperatureVal = 0;
        int scale = 1;
        final byte firstByte = temperatureBytes[0];
        int isNegative = firstByte == '-' ? -1 : 1;
        if (isNegative == 1) {
            temperatureVal = firstByte - '0';
            scale = 10;
        }
        for (int i = 1; i < temperatureBytesLength; i++) {
            byte b = temperatureBytes[i];
            if (b == '.') {
                continue;
            }
            temperatureVal = temperatureVal * scale + b - '0';
            scale = 10;
        }
        return temperatureVal * isNegative;
    }

    private static void processLine(byte[] locationBytes, int locationBytesLength, byte[] temperatureBytes,
            int temperatureBytesLength) {
        String name = new String(locationBytes, 0, locationBytesLength);
        int temperature = parseTemperature(temperatureBytes, temperatureBytesLength);
        records.compute(name, (k, v) -> v == null ? new Row(temperature) : v.update(temperature));
    }

    private static void readFile(long startAddress, long endAddress) {
        long currentOffset = startAddress;
        // A location can be max 100 length, but since it is UTF-8, each character can take upto 4 bytes worst case -
        // therefore 512 bytes
        byte[] locationBytes = new byte[512];
        // Temperature can be max 5 bytes, allocating next power of 2
        byte[] temperatureBytes = new byte[8];
        int locationArrayOffset = 0;
        int temperatureArrayOffset = 0;
        byte b;
        while (currentOffset < endAddress) {
            while ((b = UNSAFE.getByte(currentOffset++)) != ';') {
                locationBytes[locationArrayOffset++] = b;
            }
            while ((b = UNSAFE.getByte(currentOffset++)) != '\n') {
                temperatureBytes[temperatureArrayOffset++] = b;
            }
            processLine(locationBytes, locationArrayOffset, temperatureBytes, temperatureArrayOffset);
            locationArrayOffset = 0;
            temperatureArrayOffset = 0;
        }

    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        readFile(startAddress, endAddress);
        System.out.println(new TreeMap<>(records));
    }
}

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
import java.util.*;
import java.util.stream.Collectors;

public class CalculateAverage_PEWorkshop6 {

    /**
     * Introduce a custom hash table to store the Row objects.
     * The hash table uses the location name as the key.
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

    private static class Table {
        // A hash table which maps a location to a Row object
        private final static int TABLE_SIZE = 1 << 17;
        private final static int TABLE_MASK = TABLE_SIZE - 1;

        private final Row[] table = new Row[TABLE_SIZE];

        public void put(Row value) {
            int hashCode = value.hashCode;
            String locationName = value.locationName;
            int index = hashCode & TABLE_MASK;
            Row r = table[index];
            if (r == null) {
                table[index] = value;
                return;
            }
            if (r.hashCode == hashCode && r.locationName.equals(locationName)) {
                r.update(value);
                return;
            }
            // handle collisions
            Row nextRow = r.next;
            while (nextRow != null) {
                if (nextRow.hashCode == hashCode && nextRow.locationName.equals(locationName)) {
                    nextRow.update(value);
                    return;
                }
                r = nextRow;
                nextRow = r.next;
            }
            r.next = value;
        }
    }

    private static final class Row {
        private int minTemp;
        private int maxTemp;
        private int count;
        private int sum;
        private final int hashCode;
        private final String locationName;
        private Row next;

        public Row(int minTemp, int maxTemp, int count, int sum, String locationName, int hashCode) {
            this.minTemp = minTemp;
            this.maxTemp = maxTemp;
            this.count = count;
            this.sum = sum;
            this.locationName = locationName;
            this.hashCode = hashCode;
        }

        public Row(int temperature, String locationName, int hashCode) {
            this(temperature, temperature, 1, temperature, locationName, hashCode);
        }

        Row update(int temperature) {
            this.minTemp = Integer.min(this.minTemp, temperature);
            this.maxTemp = Integer.max(this.maxTemp, temperature);
            this.count++;
            this.sum += temperature;
            return this;
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

        public Row update(Row value) {
            this.minTemp = Math.min(this.minTemp, value.minTemp);
            this.maxTemp = Math.max(this.maxTemp, value.maxTemp);
            this.count += value.count;
            this.sum += value.sum;
            return this;
        }
    }

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
            int temperatureBytesLength, Table records) {
        String name = new String(locationBytes, 0, locationBytesLength);
        int temperature = parseTemperature(temperatureBytes, temperatureBytesLength);
        final int hashCode = name.hashCode();
        records.put(new Row(temperature, name, hashCode));
    }

    private static Table readFile(long startAddress, long endAddress) {
        Table table = new Table();
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
            processLine(locationBytes, locationArrayOffset, temperatureBytes, temperatureArrayOffset, table);
            locationArrayOffset = 0;
            temperatureArrayOffset = 0;
        }
        return table;
    }

    public static void main(String[] args) throws IOException {
        String filename = args.length > 0 ? args[0] : FILE_NAME;
        FileChannel fc = FileChannel.open(Paths.get(filename), StandardOpenOption.READ);
        final long fileSize = fc.size();
        final long startAddress = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, Arena.global()).address();
        final long endAddress = startAddress + fileSize;
        final long[][] segments = findSegments(startAddress, endAddress, fileSize,
                fileSize > 1024 * 1024 * 1024 ? 8 : 1);
        final List<Table> maps = Arrays.stream(segments).parallel().map(s -> readFile(s[0], s[1]))
                .collect(Collectors.toList());
        Table mergedMap = mergeMapsInParallel(maps);
        TreeMap<String, Row> finalMap = new TreeMap<>();
        for (Row r : mergedMap.table) {
            if (r != null) {
                finalMap.put(r.locationName, r);
                while (r.next != null) {
                    finalMap.put(r.next.locationName, r.next);
                    r = r.next;
                }
            }
        }
        System.out.println(finalMap);
    }

    private static Table mergeMapsInParallel(List<Table> tables) {
        // assuming tables.length is a multiple of 2
        while (tables.size() > 1) {
            List<Table[]> pairs = new ArrayList<>();
            for (int i = 0; i < tables.size(); i += 2) {
                final Table[] e = new Table[2];
                e[0] = tables.get(i);
                e[1] = tables.get(i + 1);
                pairs.add(e);
            }
            tables = pairs.parallelStream().map(p -> {
                final Table t1 = p[0];
                final Table t2 = p[1];
                for (Row r : t2.table) {
                    if (r == null) {
                        continue;
                    }
                    t1.put(r);
                    while (r.next != null) {
                        t1.put(r.next);
                        r = r.next;
                    }
                }
                return t1;
            }).collect(Collectors.toList());
        }
        return tables.get(0);
    }

    private static long[][] findSegments(long startAddress, long endAddress, long size, int segmentCount) {
        if (segmentCount == 1) {
            return new long[][] { { startAddress, endAddress } };
        }
        long[][] segments = new long[segmentCount][2];
        long segmentSize = size / segmentCount + 1;
        int i = 0;
        long currentOffset = startAddress;
        while (currentOffset < endAddress) {
            segments[i][0] = currentOffset;
            currentOffset += segmentSize;
            currentOffset = Math.min(currentOffset, endAddress);
            if (currentOffset >= endAddress) {
                segments[i][1] = endAddress;
                break;
            }
            while (UNSAFE.getByte(currentOffset) != '\n') {
                currentOffset++;
                // align to newline boundary
            }
            segments[i++][1] = currentOffset++;
        }
        return segments;
    }
}

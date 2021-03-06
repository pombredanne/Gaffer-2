/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.gov.gchq.gaffer.sketches.datasketches.sampling.serialisation;

import com.yahoo.sketches.sampling.ReservoirLongsSketch;
import com.yahoo.sketches.sampling.ReservoirLongsUnion;
import org.junit.Test;
import uk.gov.gchq.gaffer.exception.SerialisationException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ReservoirLongsUnionSerialiserTest {
    private static final ReservoirLongsUnionSerialiser SERIALISER = new ReservoirLongsUnionSerialiser();

    @Test
    public void testSerialiseAndDeserialise() {
        final ReservoirLongsUnion union = ReservoirLongsUnion.getInstance(20);
        union.update(1L);
        union.update(2L);
        union.update(3L);
        testSerialiser(union);

        final ReservoirLongsUnion emptyUnion = ReservoirLongsUnion.getInstance(20);
        testSerialiser(emptyUnion);
    }

    private void testSerialiser(final ReservoirLongsUnion union) {
        final ReservoirLongsSketch result = union.getResult();
        final boolean resultIsNull = result == null;
        long[] sample = new long[]{};
        if (!resultIsNull) {
            sample = union.getResult().getSamples();
        }
        final byte[] unionSerialised;
        try {
            unionSerialised = SERIALISER.serialise(union);
        } catch (final SerialisationException exception) {
            fail("A SerialisationException occurred");
            return;
        }

        final ReservoirLongsUnion unionDeserialised;
        try {
            unionDeserialised = SERIALISER.deserialise(unionSerialised);
        } catch (final SerialisationException exception) {
            fail("A SerialisationException occurred");
            return;
        }
        final ReservoirLongsSketch deserialisedResult = unionDeserialised.getResult();
        if (deserialisedResult == null) {
            assertTrue(resultIsNull);
        } else {
            assertArrayEquals(sample, unionDeserialised.getResult().getSamples());
        }
    }

    @Test
    public void testCanHandleReservoirLongsUnion() {
        assertTrue(SERIALISER.canHandle(ReservoirLongsUnion.class));
        assertFalse(SERIALISER.canHandle(String.class));
    }
}

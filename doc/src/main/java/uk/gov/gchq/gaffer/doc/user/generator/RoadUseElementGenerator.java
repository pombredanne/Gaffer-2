/*
 * Copyright 2016 Crown Copyright
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

package uk.gov.gchq.gaffer.doc.user.generator;

import uk.gov.gchq.gaffer.data.element.Edge;
import uk.gov.gchq.gaffer.data.element.Element;
import uk.gov.gchq.gaffer.data.generator.OneToOneElementGenerator;

public class RoadUseElementGenerator implements OneToOneElementGenerator<String> {
    @Override
    public Element _apply(final String line) {
        final String[] t = line.split(",");

        // Ignore the road name
        final String junctionA = t[1];
        final String junctionB = t[2];

        return new Edge.Builder()
                .group("RoadUse")
                .source(junctionA)
                .dest(junctionB)
                .directed(true)
                .property("count", 1L)
                .build();
    }
}

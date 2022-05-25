/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.buildinit.interrogator.model.question;

import org.gradle.api.internal.tasks.userinput.UserInputHandler;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChoiceQuestion extends Question {

    private Map<String, Object> choices;

    public Map<String, Object> getChoices() {
        return choices;
    }

    public void setChoices(Map<String, Object> choices) {
        this.choices = choices;
    }

    @Override
    public Object ask(UserInputHandler userInputHandler) {
        List<Choice> choices = getChoices()
            .entrySet()
            .stream()
            .map(entry -> new Choice(entry.getValue(), entry.getKey()))
            .collect(Collectors.toList());

        Choice selectedChoice = userInputHandler.selectOption(
            getQuestion(),
            choices,
            choices.get(0)
        );

        return selectedChoice.value;
    }

    public static class Choice {
        private Object option;
        private String value;

        public Choice(Object option, String value) {
            this.option = option;
            this.value = value;
        }

        public Object getOption() {
            return option;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return option.toString();
        }
    }
}

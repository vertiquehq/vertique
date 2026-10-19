-- SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
-- SPDX-License-Identifier: EUPL-1.2
CREATE TABLE broken_item (id INTEGER PRIMARY KEY);
INSERT INTO broken_item_that_does_not_exist (id) VALUES (1);

#!/usr/bin/env python3
"""Generates a realistic but entirely FAKE German WhatsApp (Android) export for tests/screenshots only."""
import random, datetime as dt
random.seed(42)
A, B = "Lena", "Jonas"
lines = ["01.11.25, 09:12 - Nachrichten und Anrufe sind Ende-zu-Ende-verschlüsselt. Niemand außerhalb dieses Chats kann sie lesen. Tippe, um mehr zu erfahren."]
openers = ["Guten Morgen ☀️", "Hey du 😊", "Na, wie war dein Tag?", "Rate mal was passiert ist", "Bist du wach?", "Mittagspause! 🍝", "Morgen 😴"]
talk = ["haha voll", "Ja klingt gut", "Ich bin so müde heute", "Was machst du heute Abend?", "Lass uns Pizza bestellen 🍕", "Hab dich lieb ❤️",
        "Bin gleich da", "Kannst du Milch mitbringen?", "Der Film war mega 😂", "Ich vermisse dich", "Okay bis später", "Na klar!",
        "Oh nein 🙈", "Wie lief die Prüfung?", "Ganz okay glaub ich", "Du bist so ein Idiot haha", "Wann kommst du?", "xD", "lol",
        "Ich koche heute Curry", "Klingt lecker 😍", "Das Wetter ist so schön", "Lass mal spazieren gehen", "Der Hund vom Nachbarn bellt schon wieder",
        "Kaffee trinken morgen?", "Unbedingt ☕", "Hast du die Serie weitergeschaut?", "Nein ohne dich doch nicht!", "Ich hab Hunger",
        "Gute Nacht 😘", "Schlaf gut ❤️", "Hahaha nein", "Ernsthaft?", "Der Bus ist schon wieder zu spät 🙄", "Ich bin stolz auf dich",
        "Pass auf dich auf", "Kuss 😘", "Alles gut bei dir?", "Ja alles gut", "Wir müssen noch das Geschenk für Mama kaufen",
        "Der Hund vom Nachbarn ist so süß", "Kaffee trinken? ☕", "Bin unterwegs", "Schick mal ein Foto", "Hast du schon gegessen?"]
media = ["<Medien ausgeschlossen>"]
start = dt.datetime(2025, 11, 1, 9, 30)
day = start
while day < dt.datetime(2026, 10, 15):
    if random.random() < 0.08:  # quiet days
        day += dt.timedelta(days=random.choice([1, 1, 2, 5]) if random.random() > 0.05 else 12); continue
    convs = random.choice([1, 2, 2, 3])
    last_t = day.replace(hour=5, minute=0)
    for hr in sorted(random.sample([7, 8, 12, 13, 17, 18, 19, 20, 21, 22, 23, 24, 25], convs)):
        t = day.replace(hour=0, minute=random.randint(0, 59)) + dt.timedelta(hours=hr)
        if t <= last_t: t = last_t + dt.timedelta(hours=7)
        who = A if random.random() < 0.58 else B
        lines.append(f"{t:%d.%m.%y, %H:%M} - {who}: {random.choice(openers)}")
        for _ in range(random.randint(3, 14)):
            if random.random() < 0.55: who = B if who == A else A
            t += dt.timedelta(seconds=random.choice([20, 40, 90, 180, 600, 1500, 4000]) * (1.7 if who == B else 1))
            r = random.random()
            if r < 0.06: msg = random.choice(media)
            elif r < 0.07: msg = "Diese Nachricht wurde gelöscht"
            elif r < 0.09: msg = "https://www.example.org/rezept-curry"
            elif r < 0.10: msg = "Weißt du noch?\nDas war so lustig\nwie damals im Urlaub 😂"
            else: msg = random.choice(talk)
            lines.append(f"{t:%d.%m.%y, %H:%M} - {who}: {msg}")
        last_t = t
    if random.random() < 0.03:  # record-ish day
        t = max(last_t, day.replace(hour=20))
        for i in range(120):
            who = A if i % 2 else B; t += dt.timedelta(seconds=45)
            lines.append(f"{t:%d.%m.%y, %H:%M} - {who}: {random.choice(talk)}")
    day += dt.timedelta(days=1)
open(__import__("os").path.join(__import__("os").path.dirname(__file__), "..", "app/src/test/resources/sample_chat.txt"), "w").write("\n".join(lines) + "\n")
print(len(lines))

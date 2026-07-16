# Mogluco

**A modern, Material 3 redesign of [Juggluco](https://www.juggluco.nl)'s phone and WearOS watch app; tested with the Freestyle Libre 3 / 3+.**

Mogluco reads glucose straight from your Libre 3 / 3+ sensor over Bluetooth and shows it in a clean, modern interface.
No account is needed, and nothing leaves your phone unless you explicitly request it (e.g., pushing sensor data to LibreView).

Mogluco is a [Juggluco](https://www.juggluco.nl) fork and, as such, uses the same sensor communication paths.
It solely packs all functionality into an entirely new Jetpack Compose UI and WearOS app to not only improve the user interface, but also the user experience by simplifying the scanning flow and settings.


<div markdown>
<img src="img/home.png" width="240" alt="Home">
<img src="img/averages.png" width="240" alt="Average day">
<img src="img/watch.png" width="240" alt="Pixel Watch">
</div>



## Where to next

- **[Installation](installation.md)** — build the phone and watch apps.
- **[Onboarding](onboarding.md)** — set up your first sensor and the watch.
- **[Features](features.md)** — a guided tour with screenshots.

!!! warning "Not a medical device"
    Glucose sensors are not always accurate.
    When a reading disagrees with how you feel, use a blood-glucose fingerstick.
    Mogluco is a data viewer and just displays what the sensor sends.
    Do not use it to make treatment decisions on its own.

    It is an independent project and is **not affiliated with Abbott or Juggluco**.

## Tested hardware

Due to the limitation of being a single individual I cannot test all devices and ensure they work properly.
The device combinations I tested Mogluco on are:

- **Libre 3+ sensor** & **Pixel Pro 10** & **Pixel Watch 3**
- **Libre 3+ sensor** & **Samsung Galaxy A56** & **Pixel Watch 3**

In case you test it on any other device combinations or want to implement support for them, please contact me or open an issue/pull request on [Github](https://github.com/woshicado/mogluco).

## Credits

Thanks to **J. Kaltes** for providing [Juggluco](https://www.juggluco.nl), which has been used as a base for this project, and simplified the development of this project.

!!! info "Generate AI Usage"
    Much of Mogluco's redesign were written with the help of **generative AI (specifically Anthropic's Claude Opus 4.8/Fable 5)**, under human direction and review.
    Therefore, this fork may contain bugs, and in particular those often encountered when using such models for programming.
    While I tried my best to test the app's functionality and work out any bugs, be aware that typical errors may be encountered.

    If you encounter any bugs, please reach out so I can look at them and fix them.


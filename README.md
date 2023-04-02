# Image2Map

**Note: This is not the official Image2Map repo, see https://github.com/TheEssem/Image2Map**  
**This fork is only created for use in The District of Joban, use at your own risk**

A Fabric mod that allows you to render an image onto a map. Inspired by the Bukkit plugin ImageOnMap.

![Some images](https://raw.githubusercontent.com/TheEssem/Image2Map/master/images.png)

To render an image, just run this command like so:
```
/mapcreate none <path to image URL>
```

or with a base resolution so it can auto-split:
```
/mapcreate none 128 <path to image URL>
```

To render an image with dithering, run this:
```
/mapcreate dither <path to image URL>
```

or with a base resolution so it can auto-split:
```
/mapcreate none 128 <path to image URL>
```

## Build

Building is simple provided you have a working JDK:
```
./gradlew build
```
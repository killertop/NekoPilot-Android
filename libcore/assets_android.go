//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"sync"

	"golang.org/x/mobile/asset"
)

var assetsMutex sync.Mutex

func extractAssets() error {
	assetsMutex.Lock()
	defer assetsMutex.Unlock()

	useOfficialAssets := intfNB4A.UseOfficialAssets()
	for _, name := range []string{geoipRuleSet, geositeRuleSet} {
		if err := extractAssetName(name, useOfficialAssets); err != nil {
			return fmt.Errorf("extract %s: %w", name, err)
		}
	}
	return nil
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	var version string
	var apkPrefix string
	switch name {
	case geoipRuleSet:
		version = geoipRuleSetVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeRuleSet:
		version = geositeRuleSetVersion
		apkPrefix = apkAssetPrefixSingBox
	}

	dir := externalAssetsPath
	dstName := dir + name

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets {
		// 官方源升级
		b, err := os.ReadFile(dir + version)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.Remove(dir + version)
		} else {
			localVersion = string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				doExtract = assetVersion != localVersion
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := dstName + ".xz"
		tmpDstName := dstName + ".tmp"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, tmpDstName)
			os.Remove(tmpXzName)
		}
		if err == nil {
			err = os.Rename(tmpDstName, dstName)
		}
		if err != nil {
			_ = os.Remove(tmpXzName)
			_ = os.Remove(tmpDstName)
		}
		return err
	}

	f, err := asset.Open(apkPrefix + name + ".xz")
	if err != nil {
		return fmt.Errorf("open compressed asset: %w", err)
	}
	if err = extractXz(f); err != nil {
		return fmt.Errorf("extract xz: %w", err)
	}
	if err = os.WriteFile(dir+version, []byte(assetVersion), 0644); err != nil {
		return fmt.Errorf("write version: %w", err)
	}
	log.Println("Extracted standard rule-set", name)
	return nil
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}

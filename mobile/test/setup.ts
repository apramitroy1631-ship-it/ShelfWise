// Test setup: wait up to 5 s for async UI. (package.json also allows 30 s per test: the first test of a cold run loads React Native.)
import { configure } from '@testing-library/react-native';

configure({ asyncUtilTimeout: 5000 });

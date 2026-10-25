"""Analytic pooling witnesses; no model weights or corpus inputs."""
import unittest
import numpy as np
from encoder_ablation_probe import pool


class PoolingWitnesses(unittest.TestCase):
    def test_masked_mean_and_first_token_are_distinct(self):
        hidden = np.array([[[3., 0.], [0., 4.], [900., -900.]]])
        mask = np.array([[1, 1, 0]])
        np.testing.assert_allclose(pool(hidden, mask, 'mean'), [[.6, .8]], rtol=0, atol=1e-15)
        np.testing.assert_allclose(pool(hidden, mask, 'cls'), [[1., 0.]], rtol=0, atol=1e-15)

    def test_missing_and_nonfinite_vectors_do_not_become_cosines(self):
        for hidden, mask, method in [
                (np.zeros((1, 2, 2)), np.ones((1, 2)), 'cls'),
                (np.ones((1, 2, 2)), np.zeros((1, 2)), 'mean'),
                (np.full((1, 2, 2), np.nan), np.ones((1, 2)), 'mean'),
                (np.ones((1, 2, 2)), np.ones((1, 3)), 'mean')]:
            with self.subTest(method=method, shape=mask.shape), self.assertRaises(ValueError):
                pool(hidden, mask, method)


if __name__ == '__main__':
    unittest.main()
